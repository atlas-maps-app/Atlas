package app.atlas.ui.search

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.atlas.Services
import app.atlas.nav.BackStackNavigator
import app.atlas.nav.Dest
import app.atlas.nav.ProvideServices
import app.atlas.testing.Fixtures
import app.atlas.testing.testServices
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Ask counts the regions off the main thread (the app maintainer, 19 November 2026, batch 6 B10): whether the phone
 * holds none, which shows "No region on this phone yet" in place of the plan, was counted in the screen's composition,
 * every store's places on the main thread, where a store filed anew waits on its lock. It is counted with the plan.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AskOffTheMainThreadTest {
    @get:Rule
    val compose = createComposeRule()

    private val sentence = "a pub with a terrace, then dinner"

    private fun open(s: Services) =
        compose.setContent { ProvideServices(s) { AskScreen(Dest.Ask(sentence), BackStackNavigator(listOf(Dest.Home()))) } }

    private fun waitFor(text: String) =
        compose.waitUntil(20_000) { compose.onAllNodes(hasText(text, substring = true, ignoreCase = true)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun askPlansWithoutCountingTheRegionsOnTheMainThread() {
        val store = StoreOffTheMainThreadTest.Watched(Fixtures.amsStore)
        val s = testServices(stores = listOf(store))
        open(s)
        waitFor("Your plan")
        compose.waitForIdle()
        assertEquals("asked on the main thread: ${store.onMain.joinToString("; ")}", 0, store.onMain.size)
        s.close()
    }

    /** The case the count is for, as before: no region at all. */
    @Test
    fun withNoRegionAskSaysSo() {
        val s = testServices()
        open(s)
        waitFor("No region on this phone yet")
        s.close()
    }
}
