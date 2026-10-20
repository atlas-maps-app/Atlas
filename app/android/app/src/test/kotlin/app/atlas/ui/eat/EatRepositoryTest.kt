package app.atlas.ui.eat

import app.atlas.Services
import app.atlas.services.PlaceOrigin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The places around the Westerkerk, as the eat screens load them: from the place store, else the map's packs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EatRepositoryTest {
    private fun load(services: Services): EatArea = runBlocking {
        withTimeout(30_000) { EatRepository.of(services).area(Services.WESTERKERK).first { !it.loading } }
    }

    @Test
    fun theStoreAnswersWithEveryTag() {
        val area = load(EatFixtures.services())
        println("store: ${area.places.size} ${area.origin} ${area.error}")
        assertEquals(PlaceOrigin.STORE, area.origin)
        assertTrue("${area.places.size} places", area.places.size > 500)
        // Nearest first, all within the radius, every one a place to eat or drink with a name.
        assertTrue(area.places.zipWithNext().all { (a, b) -> a.distanceM <= b.distanceM })
        assertTrue(area.places.all { it.distanceM <= EatArea.RADIUS_M && it.kind != EatKind.UNKNOWN && it.name.isNotBlank() })
        assertEquals("NL", area.country)
        assertTrue(area.hoursShare() > 0.3)
    }

    @Test
    fun refreshLoadsAgainIntoTheSameFlow() {
        val services = EatFixtures.services()
        val repo = EatRepository.of(services)
        val flow = repo.area(Services.WESTERKERK)
        val first = runBlocking { withTimeout(30_000) { flow.first { !it.loading } } }
        repo.refresh(Services.WESTERKERK)
        val again = runBlocking { withTimeout(30_000) { flow.first { !it.loading } } }
        assertTrue(again !== first)
        assertEquals(first.places.size, again.places.size)
        // The same flow is handed out again; nothing is loaded twice for a point close by.
        assertTrue(repo.area(Services.WESTERKERK).value === flow.value)
    }

    @Test
    fun withoutAStoreTheBundledPackAnswersWithHours() {
        val area = load(EatFixtures.services(places = false))
        println("pack: ${area.places.size} ${area.origin} ${area.error}")
        assertEquals(PlaceOrigin.PACK, area.origin)
        assertTrue("${area.places.size} places", area.places.size > 50)
        // the pack keeps every tag: the opening hours too
        assertTrue(area.places.count { it.hasHours } > 20)
    }
}
