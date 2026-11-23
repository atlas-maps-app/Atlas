package app.atlas.ui.search

import android.os.Looper
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.atlas.Services
import app.atlas.geo.LatLon
import app.atlas.model.Place
import app.atlas.nav.BackStackNavigator
import app.atlas.nav.Dest
import app.atlas.nav.ProvideServices
import app.atlas.search.SearchItem
import app.atlas.search.SearchResults
import app.atlas.search.TravelMode
import app.atlas.testing.testServices
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.LocalDateTime

/**
 * An online find among typed results says whether it is open, its hours read where it is, as its pin fades by them
 * (the app maintainer, 18 November 2026, batch 4 A4: the row passed no status and PlaceLine no longer reads one, so
 * the row said nothing while its pin faded).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnlineTypedRowsTest {
    @get:Rule
    val compose = createComposeRule()

    private val here = Services.WESTERKERK

    private fun until(done: () -> Boolean) {
        val end = System.currentTimeMillis() + 20_000
        while (!done() && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            Thread.sleep(20)
        }
        assertTrue("timed out", done())
    }

    @Test
    fun anOnlineFindSaysWhetherItIsOpen() {
        // the test's evening: Tuesday 13 October 2026, 19:30 in Amsterdam
        val s = testServices()
        val cafe = Place(null, LatLon(here.lat + 0.001, here.lon), mapOf("name" to "Café Online", "amenity" to "cafe", "opening_hours" to "Mo-Su 08:00-23:00"), source = "photon")
        val bakery = Place(null, LatLon(here.lat - 0.001, here.lon), mapOf("name" to "Bakkerij Online", "shop" to "bakery", "opening_hours" to "Mo-Sa 07:00-17:00"), source = "photon")
        val vm = SearchViewModel(
            s, "online", null, { here }, "en",
            search = { t, _ -> SearchResults(t, listOf(SearchItem.Online(cafe, 110.0, "ui.pin", 1.0), SearchItem.Online(bakery, 110.0, "ui.pin", 0.9)), usedOnline = true) },
            online = { true },
        )
        until { vm.state.value.results != null && !vm.state.value.searching }
        val now = LocalDateTime.now(s.clock)
        compose.setContent {
            ProvideServices(s) {
                LazyColumn { typedPane(vm.state.value, vm, "online", here, TravelMode.WALK, "en", now, BackStackNavigator(listOf(Dest.Home())), {}, {}) }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Café Online").assertExists()
        compose.onNodeWithText("open until 23:00", substring = true).assertExists()
        compose.onNodeWithText("closed · opens", substring = true).assertExists()
        s.close()
    }
}
