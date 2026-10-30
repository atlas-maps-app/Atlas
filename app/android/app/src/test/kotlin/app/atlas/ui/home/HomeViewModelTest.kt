package app.atlas.ui.home

import android.os.Looper
import app.atlas.Services
import app.atlas.geo.LatLon
import app.atlas.mapview.CameraPosition
import app.atlas.mapview.HomeView
import app.atlas.mapview.Follow
import app.atlas.mapview.MapLayer
import app.atlas.model.Fix
import app.atlas.providers.Http
import app.atlas.providers.Weather
import app.atlas.services.GnssInfo
import app.atlas.services.LocationQuality
import app.atlas.services.LocationSource
import app.atlas.services.PlaceOrigin
import app.atlas.services.SavedCamera
import app.atlas.services.SettingsKeys
import app.atlas.testing.keepFixtureForecast
import app.atlas.testing.testServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** The Map home's state: where the camera starts, what it saves, the locate button, 3D, and a category's places. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeViewModelTest {
    private fun idle(ms: Long = 50) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** Runs the main looper until [done] or the time is up, letting background threads work meanwhile. */
    private fun until(timeoutMs: Long = 10_000, done: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!done() && System.currentTimeMillis() < end) {
            idle()
            Thread.sleep(20)
        }
        assertTrue("timed out", done())
    }

    @Test
    fun startsWhereThePhoneIsAndFocusWins() {
        val s = testServices()
        val vm = HomeViewModel(s, null, null)
        // At the Westerkerk the first look is the Main board's own view of the canal ring, with you in it.
        assertEquals(HomeView.camera, vm.map.camera)
        val focus = LatLon(52.3600, 4.8852)
        val vm2 = HomeViewModel(s, focus, 18.0)
        assertEquals(CameraPosition(focus, 18.0, 0.0, HomeViewModel.DEFAULT_PITCH), vm2.map.camera)
        assertEquals(focus, vm2.focus)
    }

    @Test
    fun theCameraIsSavedAfterItStops() {
        val s = testServices()
        val vm = HomeViewModel(s, null, null)
        val moved = CameraPosition(LatLon(52.37, 4.89), 17.25, 30.0, 45.0)
        vm.onCameraMove(moved, byUser = true)
        until { runBlocking { s.settings.camera.first() } != null }
        assertEquals(SavedCamera(moved.target, 17.25, 30.0, 45.0), runBlocking { s.settings.camera.first() })
    }

    @Test
    fun locateCyclesAndPanningStopsFollowing() {
        val vm = HomeViewModel(testServices(), null, null)
        assertEquals(Follow.NONE, vm.map.follow)
        vm.locate()
        assertEquals(Follow.POSITION, vm.map.follow)
        vm.locate()
        assertEquals(Follow.HEADING, vm.map.follow)
        vm.locate()
        assertEquals(Follow.POSITION, vm.map.follow)
        vm.onCameraMove(vm.map.camera.copy(zoom = 15.0), byUser = true)
        assertEquals(Follow.NONE, vm.map.follow)
    }

    @Test
    fun threeDIsASetting() {
        val s = testServices()
        val vm = HomeViewModel(s, null, null)
        vm.toggle3d()
        until { runBlocking { s.settings.flow(SettingsKeys.MAP_3D).first() } == false }
        until { MapLayer.BUILDINGS_3D !in vm.style.value.layers }
        vm.toggle3d()
        until { runBlocking { s.settings.flow(SettingsKeys.MAP_3D).first() } == true }
    }

    @Test
    fun threeDBringsBackTheTiltLastSetWithTheFingers() {
        val s = testServices()
        val vm = HomeViewModel(s, null, null)
        // until the fingers have set one, the 3D button's own
        vm.toggle3d()
        until { vm.map.camera.pitch == 0.0 && MapLayer.BUILDINGS_3D !in vm.style.value.layers }
        vm.toggle3d()
        until { vm.map.camera.pitch == HomeViewModel.DEFAULT_PITCH && MapLayer.BUILDINGS_3D in vm.style.value.layers }
        // the user tilts to 33° with their fingers: flat, then 3D again brings back 33°
        vm.map.chosenTilt = 33.0
        vm.toggle3d()
        until { vm.map.camera.pitch == 0.0 && MapLayer.BUILDINGS_3D !in vm.style.value.layers }
        vm.toggle3d()
        until { vm.map.camera.pitch == 33.0 }
        // kept once the camera rests, and the next launch's button has it too
        vm.onCameraMove(vm.map.camera, byUser = true)
        until { runBlocking { s.settings.flow(SettingsKeys.MAP_TILT).first() } == 33.0 }
        val again = HomeViewModel(s, null, null)
        until { again.map.chosenTilt == 33.0 }
    }

    @Test
    fun theSkyNowComesFromTheKeptForecast() {
        // Offline, at the fixed evening of the tests: the forecast kept on the phone gives the chip its sky.
        val s = testServices().apply { assertTrue(keepFixtureForecast()) }
        val vm = HomeViewModel(s, null, null)
        val watcher = CoroutineScope(Dispatchers.Default).launch { vm.weather.collect {} }
        try {
            until { vm.weather.value != null }
            val w = vm.weather.value!!
            assertEquals(17.0, w.temperature, 0.5)
            assertTrue(w.kept)
            assertTrue("after sunset it is night", !w.isDay)
        } finally {
            watcher.cancel()
        }
    }

    @Test
    fun aCategoryShowsItsPlacesAndTappingItAgainClears() {
        val vm = HomeViewModel(testServices(places = true), null, null)
        vm.selectCategory("groceries")
        // The sheet appears at once, loading; the store may answer before the next line runs, so do not insist on loading.
        assertNotNull(vm.results.value)
        until { vm.results.value?.loading == false }
        val r = vm.results.value!!
        assertEquals("groceries", r.categoryId)
        assertEquals(PlaceOrigin.STORE, r.source)
        assertTrue(r.places.isNotEmpty() && r.places.size <= HomeViewModel.MAX_RESULTS)
        vm.selectCategory("groceries")
        assertNull(vm.results.value)
    }

    /** A GPS that never finds a fix and counts who is listening. */
    private class CountingGps : LocationSource {
        val listening = AtomicInteger()
        override val last = MutableStateFlow<Fix?>(null)
        override val gnss = MutableStateFlow<GnssInfo?>(null)
        override val permitted = MutableStateFlow(true)
        override fun refreshPermission() = Unit
        override fun fixes(quality: LocationQuality): Flow<Fix> = flow {
            listening.incrementAndGet()
            try {
                awaitCancellation()
            } finally {
                listening.decrementAndGet()
            }
        }
    }

    @Test
    fun theGpsStopsWhenTheMapIsNotOnScreen() {
        val s = testServices()
        val gps = CountingGps()
        s.location.use(gps)
        until { s.location.last.value == null }
        val vm = HomeViewModel(s, null, null)
        // The screen watches the puck, the chip and the moments.
        val screen = CoroutineScope(Dispatchers.Main).launch {
            launch { vm.location.collect {} }
            launch { vm.weather.collect {} }
            launch { vm.moments.collect {} }
        }
        until { gps.listening.get() > 0 }
        // The screen goes (another screen on top, or the app in the background): a few seconds later the GPS is off,
        // even indoors where no fix ever came.
        screen.cancel()
        idle(6_000)
        until { gps.listening.get() == 0 }
    }

    @Test
    fun aForecastThatWasMissingIsLookedForAgain() {
        // Nothing kept on the phone at first: the chip is empty. A forecast that arrives later (kept by another screen,
        // or the network back) shows without moving the map.
        val s = testServices()
        val vm = HomeViewModel(s, null, null)
        val watcher = CoroutineScope(Dispatchers.Main).launch { vm.weather.collect {} }
        try {
            // Let the first look (an empty folder, off the main thread) finish before the forecast appears.
            repeat(10) {
                idle(500)
                Thread.sleep(100)
            }
            assertNull(vm.weather.value)
            assertTrue(s.keepFixtureForecast())
            repeat(35) { idle(60_000) }
            until { vm.weather.value != null }
        } finally {
            watcher.cancel()
        }
    }

    @Test
    fun aBrokenWeatherAnswerFallsBackToTheKeptForecast() = runBlocking {
        // A captive portal answers the forecast request with its login page (status 200): the kept forecast stands in,
        // and nothing is thrown at the screen.
        val server = MockWebServer().apply { start() }
        try {
            repeat(4) { server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "text/html").body("<html>Log in</html>").build()) }
            val cache = Files.createTempDirectory("atlas-weather").toFile()
            val weather = Weather(Http(), cache, base = server.url("/").toString(), metBase = "http://127.0.0.1:9")
            assertNull(forecastOrKept(weather, Services.WESTERKERK, online = true))
            val fixture = File(System.getProperty("atlas.repo")!!, "app/android/providers/src/test/resources/fixtures/meteo_forecast.json")
            File(cache, "weather").mkdirs()
            val name = String.format(java.util.Locale.ROOT, "forecast_%.2f_%.2f.json", Services.WESTERKERK.lat, Services.WESTERKERK.lon)
            File(cache, "weather/$name").writeText("${java.time.Instant.now().epochSecond}\n${fixture.readText()}")
            val kept = forecastOrKept(weather, Services.WESTERKERK, online = true)
            assertNotNull(kept)
            assertTrue(kept!!.kept)
            assertNotNull(forecastOrKept(weather, Services.WESTERKERK, online = false))
        } finally {
            server.close()
        }
    }

    @Test
    fun theStandInViewOfAFirstLaunchIsNotSaved() {
        // First launch, no fix yet (the location not allowed, or indoors): the map shows the board's view of the canal
        // ring meanwhile. That view is not kept, so the next launch still goes to the phone's first fix instead of
        // staying in Amsterdam.
        val s = testServices()
        s.location.use(CountingGps())
        until { s.location.last.value == null }
        val vm = HomeViewModel(s, null, null)
        assertEquals(HomeView.camera, vm.map.camera)
        repeat(5) {
            idle(1_000)
            Thread.sleep(50)
        }
        assertNull(runBlocking { s.settings.camera.first() })
        // Once the user moves the map, the view is theirs and is kept.
        vm.onCameraMove(vm.map.camera.copy(zoom = 15.0), byUser = true)
        until { runBlocking { s.settings.camera.first() } != null }
    }

    @Test
    fun aSettingThatCannotBeWrittenDoesNotTakeTheMapDown() {
        // A full or read-only disk: the 3D button cannot keep its setting. On a phone an exception escaping the view
        // model's scope ends the app; here it would reach the main thread's handler.
        val root = Files.createTempDirectory("atlas-ro").toFile()
        val s = testServices(root = root)
        File(root, "settings").apply { mkdirs(); setWritable(false) }
        val main = Thread.currentThread()
        val before = main.uncaughtExceptionHandler
        var crashed: Throwable? = null
        main.setUncaughtExceptionHandler { _, e -> crashed = e }
        try {
            val vm = HomeViewModel(s, null, null)
            vm.toggle3d()
            repeat(10) {
                idle(200)
                Thread.sleep(50)
            }
            assertNull("crashed: $crashed", crashed)
            assertTrue(MapLayer.BUILDINGS_3D in vm.style.value.layers)
        } finally {
            main.uncaughtExceptionHandler = before
            File(root, "settings").setWritable(true)
        }
    }
}
