package app.atlas.ui.mine

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.atlas.Services
import app.atlas.nav.AtlasRoot
import app.atlas.nav.BackStackNavigator
import app.atlas.nav.Dest
import app.atlas.services.RegionLayer
import app.atlas.testing.keepFixtureForecast
import app.atlas.testing.settle
import app.atlas.testing.shotsDir
import app.atlas.testing.testServices
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * Every mine screen, through the real navigation, on the test phone at the Westerkerk: one PNG each to look at beside its
 * board (`cache/pictures/img/<Board>.jpg`): Mine, Offline, Settings, Privacy, DataSources, Satellites, Watch, Weather,
 * Forecast, Overlay (the map's look), Plugs and Charge, Pump, Power, Sources (water) and Welcome.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class MineScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun phone(places: Boolean = true): Services = testServices(places = places).apply { keepFixtureForecast() }

    private fun show(dest: Dest, services: Services, dark: Boolean = false): BackStackNavigator {
        val nav = BackStackNavigator(listOf(Dest.Home(), dest))
        compose.setContent { AtlasRoot(services, nav, dark = dark) }
        compose.settle(30_000)
        return nav
    }

    /** Waits for the estimate card to stop saying it is working. */
    private fun estimated() {
        compose.waitUntil(20_000) { compose.onAllNodesWithText("working it out…").fetchSemanticsNodes().isEmpty() }
        Thread.sleep(2000) // the map's dots come from the place stores in the background
        compose.waitForIdle()
    }

    private fun capture(name: String): File {
        repeat(4) { compose.waitForIdle(); Thread.sleep(120) }
        compose.settle()
        val f = File(shotsDir, "$name.png")
        compose.onRoot().captureRoboImage(f.absolutePath)
        assertTrue(f.isFile && f.length() > 1000)
        return f
    }

    @Test
    fun offline() {
        val s = phone()
        val at = s.clock.instant().minus(Duration.ofHours(2))
        MineFixtures.region(s, "Amsterdam", MineFixtures.AMSTERDAM, mapMb = 410, placesMb = 180, routeMb = 310, at = at, voices = listOf("en", "nl"))
        MineFixtures.region(s, "Haarlem", MineFixtures.HAARLEM, mapMb = 120, placesMb = 24, at = at.minus(Duration.ofDays(3)))
        val lisbon = MineFixtures.region(s, "Lisbon", MineFixtures.LISBON, mapMb = 0, at = at)
        s.mine.useDeps(MineFixtures.offlineDeps(s, s.clock.instant()))
        s.mine.engine.refresh()
        s.mine.engine.report(lisbon.id, RegionLayer.MAP, 0.64f)
        show(Dest.Offline, s)
        capture("mine_offline")
    }

    @Test
    fun offlineCountry() {
        // the Maps board with the Netherlands' card: what the map took as it was looked at, and the country whole
        val s = phone()
        s.mine.useDeps(MineFixtures.countryDeps(s, s.clock.instant()))
        s.mine.engine.refresh()
        show(Dest.Offline, s)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Keep it all offline", substring = true).fetchSemanticsNodes().isNotEmpty() }
        capture("mine_offline_country")
        // its button opens the country list with the Netherlands picked, at the size it takes: tiles, index and places
        compose.onNodeWithText("Keep it all offline", substring = true).performClick()
        compose.waitForIdle()
    }

    @Test
    fun mine() {
        val s = phone()
        val at = s.clock.instant().minus(Duration.ofHours(2))
        MineFixtures.region(s, "Amsterdam", MineFixtures.AMSTERDAM, mapMb = 410, placesMb = 180, routeMb = 310, at = at)
        MineFixtures.region(s, "Haarlem", MineFixtures.HAARLEM, mapMb = 120, placesMb = 24, at = at)
        s.mine.useDeps(MineFixtures.offlineDeps(s, s.clock.instant()))
        runBlocking { s.settings.set(MineKeys.EV_MODEL, app.atlas.providers.EvModels.bundled().all.first { it.make == "Tesla" }.id) }
        // Where you have been, on, as the board's note shows it: 41 places this year, 300 m apart along the canals.
        runBlocking { s.settings.set(MineKeys.TRAIL, true) }
        val day = s.clock.instant().minus(Duration.ofDays(2))
        for (i in 0 until 41) s.mine.trail.record(app.atlas.geo.LatLon(52.36 + (i % 7) * 0.003, 4.87 + (i / 7) * 0.0045), day.plusSeconds(i * 600L))
        show(Dest.Mine, s)
        capture("mine_desk")
    }

    @Test
    fun settings() {
        val s = phone()
        runBlocking { s.go.store.setHome(app.atlas.ui.go.SavedPlace("Singel 120", Services.WESTERKERK)) }
        show(Dest.Settings(), s)
        capture("mine_settings")
    }

    @Test
    fun privacy() {
        show(Dest.Privacy, phone())
        capture("mine_privacy")
    }

    @Test
    fun dataSources() {
        val s = phone()
        runBlocking { s.keys.set("foursquare", "fsq-test-key") }
        show(Dest.DataSources(), s)
        capture("mine_datasources")
    }

    @Test
    fun about() {
        show(Dest.About, phone())
        capture("mine_about")
    }

    @Test
    fun newPhone() {
        show(Dest.NewPhone, phone())
        capture("mine_newphone")
    }

    @Test
    fun satellites() {
        val s = phone()
        s.location.use(MineFixtures.GnssPhone(Services.WESTERKERK, MineFixtures.sky()))
        show(Dest.Satellites, s)
        capture("mine_satellites")
    }

    @Test
    fun watch() {
        show(Dest.Watch, phone())
        capture("mine_watch")
    }

    @Test
    fun weather() {
        val s = phone()
        val now = s.clock.instant()
        s.mine.pressure.add(1009.2, now.minus(Duration.ofHours(4)))
        s.mine.pressure.add(1007.1, now.minus(Duration.ofHours(2)))
        s.mine.pressure.add(1004.3, now.minusSeconds(60))
        show(Dest.Weather(), s)
        capture("mine_weather")
    }

    @Test
    fun forecast() {
        val s = phone()
        MineFixtures.keepModels(s)
        show(Dest.Forecast, s)
        capture("mine_forecast")
    }

    @Test
    fun layers() {
        // By day, so the time-of-light scrubber sits mid-way and the 3D shadows are short.
        val noon = java.time.Clock.fixed(java.time.Instant.parse("2026-10-14T11:30:00Z"), java.time.ZoneId.of("Europe/Amsterdam"))
        show(Dest.Layers, testServices(places = true, clock = noon).apply { keepFixtureForecast() })
        capture("mine_layers")
    }

    @Test
    fun charge() {
        val s = MineFixtures.phoneWithRoadside()
        val model = app.atlas.providers.EvModels.bundled().all.first { it.make == "Tesla" && it.model.contains("Model 3") }
        runBlocking { s.settings.set(MineKeys.EV_MODEL, model.id); s.settings.set(MineKeys.EV_SOC, 0.38f) }
        show(Dest.Charge(Services.WESTERKERK.lat, Services.WESTERKERK.lon), s)
        capture("mine_charge")
    }

    @Test
    fun chargeCard() {
        val s = MineFixtures.phoneWithRoadside()
        val model = app.atlas.providers.EvModels.bundled().all.first { it.make == "Tesla" && it.model.contains("Model 3") }
        runBlocking { s.settings.set(MineKeys.EV_MODEL, model.id); s.settings.set(MineKeys.EV_SOC, 0.37f) }
        show(Dest.Charge(Services.WESTERKERK.lat, Services.WESTERKERK.lon, select = "n9000001"), s)
        capture("mine_charge_card")
    }

    @Test
    fun pump() {
        show(Dest.Pump(Services.WESTERKERK.lat, Services.WESTERKERK.lon), MineFixtures.phoneWithRoadside())
        capture("mine_pump")
    }

    @Test
    fun power() {
        val s = MineFixtures.phoneWithRoadside()
        runBlocking { s.settings.set(MineKeys.POWER_BANK_MAH, 20000) }
        // The phone's own battery, as the system announces it: 62 %, not charging.
        org.robolectric.RuntimeEnvironment.getApplication().sendStickyBroadcast(
            android.content.Intent(android.content.Intent.ACTION_BATTERY_CHANGED).putExtra(android.os.BatteryManager.EXTRA_LEVEL, 62).putExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
                .putExtra(android.os.BatteryManager.EXTRA_STATUS, android.os.BatteryManager.BATTERY_STATUS_DISCHARGING),
        )
        show(Dest.Power, s)
        capture("mine_power")
    }

    @Test
    fun water() {
        val s = MineFixtures.phoneWithRoadside()
        runBlocking { s.settings.set(MineKeys.WATER_BOTTLE_ML, 1000); s.settings.set(MineKeys.WATER_LEFT_ML, 400) }
        show(Dest.Water(Services.WESTERKERK.lat, Services.WESTERKERK.lon), s)
        capture("mine_water")
    }

    private fun withPacks(): Services = phone().also { it.mine.useDeps(MineFixtures.packDeps(it, it.clock.instant())) }

    @Test
    fun welcomeLocation() {
        val s = phone()
        show(Dest.Onboarding(0), s)
        capture("mine_welcome_1")
    }

    @Test
    fun welcomeRegion() {
        val s = withPacks()
        show(Dest.Onboarding(1), s)
        estimated()
        capture("mine_welcome_2")
    }

    @Test
    fun welcomeVoice() {
        val s = phone()
        runBlocking { s.settings.set(MineKeys.VOICE_LANGS, setOf("en", "nl")) }
        show(Dest.Onboarding(2), s)
        capture("mine_welcome_3")
    }

    @Test
    fun pickerArea() {
        val s = withPacks()
        show(Dest.RegionPicker(Services.WESTERKERK.lat, Services.WESTERKERK.lon, "area"), s)
        estimated()
        capture("mine_picker_area")
    }

    @Test
    fun pickerLasso() {
        val s = withPacks()
        show(Dest.RegionPicker(Services.WESTERKERK.lat, Services.WESTERKERK.lon, "area"), s)
        // A finger draws a loop on the map: the area becomes the box around it.
        compose.onNodeWithContentDescription("Map of the area to take offline").performTouchInput {
            val c = center
            val r = width / 5f
            down(c + androidx.compose.ui.geometry.Offset(-r, -r * 0.6f))
            for (p in listOf(androidx.compose.ui.geometry.Offset(r, -r * 0.8f), androidx.compose.ui.geometry.Offset(r * 1.2f, r * 0.7f), androidx.compose.ui.geometry.Offset(-r * 0.6f, r), androidx.compose.ui.geometry.Offset(-r, -r * 0.6f))) moveTo(c + p)
            up()
        }
        estimated()
        capture("mine_picker_lasso")
        compose.onNodeWithText("Clear").assertExists()
    }

    @Test
    fun pickerCountry() {
        val s = withPacks()
        show(Dest.RegionPicker(mode = "country"), s)
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Netherlands").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Netherlands").performClick()
        estimated()
        capture("mine_picker_country")
    }

    @Test
    fun pickerRoute() {
        val s = withPacks()
        compose.runOnUiThread { MineFixtures.startTrip(s, androidx.test.core.app.ApplicationProvider.getApplicationContext()) }
        show(Dest.RegionPicker(mode = "route"), s)
        estimated()
        capture("mine_picker_route")
    }

    @Test
    fun pickerRouteNone() {
        val s = withPacks()
        show(Dest.RegionPicker(mode = "route"), s)
        capture("mine_picker_route_none")
    }

    /** The charging route page with a planned trip (the planner's own tags on the stops), without needing routing data. */
    private fun chargingRoute(name: String, car: app.atlas.ui.mine.charge.Car, state: (app.atlas.model.Route) -> app.atlas.ui.mine.charge.TripState) {
        val s = phone()
        val base = MineFixtures.trip()
        fun stop(i: Int, id: Long, n: String, kw: Int, arrive: Double, depart: Double, mins: Double): app.atlas.model.Place {
            val at = base.points[(base.points.size * i / 100).coerceAtMost(base.points.size - 1)]
            return app.atlas.model.Place(
                app.atlas.model.OsmId(app.atlas.model.OsmType.NODE, id), at, mapOf("amenity" to "charging_station", "name" to n),
                extra = mapOf("atlas:power_kw" to "$kw", "atlas:arrive_soc" to "$arrive", "atlas:depart_soc" to "$depart", "atlas:charge_minutes" to "$mins"),
            )
        }
        val route = base.copy(
            stops = listOf(stop(38, 9_500_001, "Voltra Hub Haarlem", 150, 0.14, 0.72, 21.0), stop(74, 9_500_002, "Ridge Supercharge", 250, 0.18, 0.65, 15.0)),
            duration = base.duration + 36 * 60,
        )
        val dest = app.atlas.ui.go.SavedPlace("Rotterdam", route.points.last())
        compose.setContent {
            app.atlas.nav.ProvideServices(s, false) {
                app.atlas.ui.mine.charge.ChargingRouteView(dest, car, state(route), BackStackNavigator(listOf(Dest.Home())), {}, {})
            }
        }
        compose.settle(10_000)
        capture(name)
    }

    @Test
    fun chargingRoute() {
        val model = app.atlas.providers.EvModels.bundled().all.first { it.make == "Tesla" && it.model.contains("Model 3") }
        chargingRoute("mine_charging_route", app.atlas.ui.mine.charge.Car(model, 0.8, setOf("type2_combo", "type2"))) { r ->
            app.atlas.ui.mine.charge.TripState.Ready(app.atlas.ui.mine.charge.ChargingTrip.of(r))
        }
    }

    @Test
    fun chargingRouteWithoutRoutingData() {
        val model = app.atlas.providers.EvModels.bundled().all.first { it.make == "Tesla" && it.model.contains("Model 3") }
        chargingRoute("mine_charging_route_nodata", app.atlas.ui.mine.charge.Car(model, 0.8, setOf("type2_combo"))) { app.atlas.ui.mine.charge.TripState.NoData(listOf("E0_N50.rd5")) }
    }

    @Test
    fun chargingRouteWithNoCar() {
        chargingRoute("mine_charging_route_nocar", app.atlas.ui.mine.charge.Car(null, 0.8, emptySet())) { app.atlas.ui.mine.charge.TripState.NoCar }
    }

    /** The boards are light only, and so are these screens: the dark theme leaves them as their boards. */
    @Test
    fun darkSettings() {
        show(Dest.Settings(), phone(), dark = true)
        capture("mine_settings_dark")
    }
}
