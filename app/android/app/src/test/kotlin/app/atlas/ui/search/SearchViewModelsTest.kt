package app.atlas.ui.search

import android.os.Looper
import app.atlas.Services
import app.atlas.model.OsmId
import app.atlas.places.FactKind
import app.atlas.providers.Wikidata
import app.atlas.search.SearchItem
import app.atlas.testing.testServices
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

/** The search package's view models on the Amsterdam extract at the Westerkerk, on a Tuesday evening, offline. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchViewModelsTest {
    private fun idle(ms: Long = 50) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** Runs the main looper until [done] or the time is up, letting background threads work meanwhile. */
    private fun until(timeoutMs: Long = 15_000, done: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!done() && System.currentTimeMillis() < end) {
            idle()
            Thread.sleep(20)
        }
        assertTrue("timed out", done())
    }

    @Test
    fun searchForgivesTyposAndChipsNarrow() {
        val s = testServices(places = true)
        val vm = SearchViewModel(s, "apothek", null, { Services.WESTERKERK }, "nl")
        until { vm.state.value.results != null && !vm.state.value.searching }
        val places = vm.state.value.results!!.items.filterIsInstance<SearchItem.PlaceResult>()
        assertTrue("found: ${places.map { it.title }}", places.any { it.title == "BENU" })

        vm.onText("cafe")
        assertTrue(vm.state.value.searching)
        until { vm.state.value.text == "cafe" && !vm.state.value.searching && vm.state.value.results != null }
        val chips = vm.state.value.results!!.refinements
        // In the app's language (Dutch here), open now first.
        assertEquals("chips: ${chips.map { it.chip.label }}", "Nu open", chips.first().chip.label)
        val open = chips.first()
        vm.toggle(open.chip.value)
        until { vm.state.value.refined != null }
        val refined = vm.state.value.refined!!
        assertTrue(refined.hits.isNotEmpty())
        assertTrue(refined.hits.all { it.open?.isOpen == true })
        // Typing again clears the chips.
        vm.onText("cafe ")
        assertTrue(vm.state.value.selected.isEmpty())
        assertNull(vm.state.value.refined)
    }

    @Test
    fun aCategorySearchKeepsToItsKind() {
        val s = testServices(places = true)
        val vm = SearchViewModel(s, "Kalkhoven", "health", { Services.WESTERKERK }, "en")
        until { vm.state.value.results != null && !vm.state.value.searching }
        assertTrue(vm.state.value.results!!.items.filterIsInstance<SearchItem.PlaceResult>().none { it.title.contains("Kalkhoven") })
    }

    @Test
    fun searchesAreRemembered() {
        val s = testServices(places = true)
        val vm = SearchViewModel(s, "", null, { Services.WESTERKERK }, "en")
        val kalkhoven = s.placeStores.get(OsmId.parse("n1147560517")!!)!!.place
        vm.remember("kalkhoven", kalkhoven)
        until { s.saved.recents.value.isNotEmpty() }
        assertEquals("kalkhoven", s.saved.recents.value.first().text)
        assertEquals(kalkhoven.key, s.saved.recents.value.first().place?.key)
    }

    @Test
    fun queryBuilderRunsTheJoinAndDialsDistances() {
        val s = testServices(places = true)
        val vm = QueryViewModel(s, "cafe near bicycle rental 200m", { Services.WESTERKERK }, "en")
        until { vm.state.value.result != null && !vm.state.value.running }
        val r = vm.state.value.result!!
        assertTrue(r.hits.isNotEmpty())
        assertTrue(r.hits.all { h -> h.near.single().distance <= 200.0 + 1e-6 })
        assertTrue(vm.state.value.circles.isNotEmpty())
        assertTrue(vm.state.value.circles.all { it.radius == 200.0 })
        val ql = vm.ql()!!
        assertTrue(ql, ql.contains("bicycle_rental") && ql.contains("around"))
        val before = r.total

        vm.setDistance(1, 50.0)
        until { vm.state.value.result !== r && !vm.state.value.running }
        assertTrue(vm.state.value.result!!.total <= before)
        assertTrue(vm.state.value.result!!.hits.all { it.near.single().distance <= 50.0 + 1e-6 })
        assertTrue(vm.ql()!!.contains("50"))
    }

    @Test
    fun queryWithWordsItCannotRead() {
        val vm = QueryViewModel(testServices(places = true), "", { Services.WESTERKERK }, "en")
        until { !vm.state.value.running }
        assertNull(vm.state.value.parsed)
        assertNull(vm.query())
        assertTrue(!vm.online())
    }

    @Test
    fun aPlaceComesWithItsFactsHoursQuestsAndKeptWikidata() {
        val s = testServices(places = true)
        // Wikidata's Westerkerk, as fetched once before (the providers' recorded answer), with its architect's name.
        val repo = File(System.getProperty("atlas.repo")!!)
        val fixtures = File(repo, "app/android/providers/src/test/resources/fixtures")
        val entity = Wikidata.parseEntity(Json.parseToJsonElement(File(fixtures, "wd_entities.json").readText()).jsonObject["entities"]!!.jsonObject["Q1130722"]!!.jsonObject)
        KeptFacts(File(s.dirs.files, "search/wikidata")).keep(entity)

        val vm = PlaceViewModel(s, null, "w99205257", Services.WESTERKERK, "Westerkerk", "en")
        until { !vm.state.value.looking && vm.state.value.wiki != null }
        val st = vm.state.value
        assertEquals("Westerkerk", st.place?.name())
        assertTrue(st.complete)
        assertNotNull(st.hours)
        // Tuesday 19:30: closed, opens on Wednesday at 11:00.
        assertEquals(false, st.status?.isOpen)
        assertEquals("NL", st.country)
        assertTrue(st.facts.any { it.kind == FactKind.WHEELCHAIR })
        assertEquals("Q1130722", st.wiki?.id)
        assertNotNull(st.wiki?.inception)
        assertEquals(listOf("Q525861"), st.wiki?.architects?.map { it.id })
        // Offline: no ratings are asked, and nothing can be sent.
        assertEquals(RatingsState.Offline, st.ratings)
        assertTrue(!vm.canSend())
    }

    @Test
    fun aPlaceOnlyFromALinkStaysHonest() {
        val vm = PlaceViewModel(testServices(), null, "n123", app.atlas.geo.LatLon(52.37, 4.88), "Somewhere", "en")
        until { !vm.state.value.looking }
        val st = vm.state.value
        assertEquals("Somewhere", st.place?.name())
        assertTrue(!st.complete)
        assertNull(st.hours)
    }

    @Test
    fun askPlansAnEveningAndRefines() {
        val s = testServices(places = true)
        val vm = AskViewModel(s, "a pub with a terrace, then dinner", { Services.WESTERKERK }, "en")
        until { vm.state.value.plan != null && !vm.state.value.running }
        val plan = vm.state.value.plan!!
        assertEquals(2, plan.stops.size)
        assertEquals(2, plan.found)
        vm.toggle(Refine.CLOSER)
        until { !vm.state.value.running && vm.state.value.refine == setOf(Refine.CLOSER) && vm.state.value.plan !== plan }
        assertTrue(vm.state.value.plan!!.walkM <= plan.walkM + 1e-6)
        vm.onText("")
        until { vm.state.value.plan == null }
    }

    @Test
    fun whatsHereFindsOnce() {
        val vm = WhatsHereViewModel(testServices(places = true), Services.WESTERKERK, "en")
        until { vm.state.value.facts != null }
        assertEquals("Westerkerk", vm.state.value.facts!!.spot?.name())
        assertEquals(Sending.Idle, vm.state.value.note)
        assertTrue(!vm.online)
    }
}
