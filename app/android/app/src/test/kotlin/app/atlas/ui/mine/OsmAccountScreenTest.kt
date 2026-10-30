package app.atlas.ui.mine

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.atlas.providers.OsmType
import app.atlas.providers.TagEdit
import app.atlas.services.EditQueue
import app.atlas.services.EditStatus
import app.atlas.services.OsmAccount
import app.atlas.testing.shotsDir
import app.atlas.ui.search.YourEditView
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant

/**
 * The OpenStreetMap account board in its states, each to `mine_osm_<state>.png`: signed out, signing in, signed in with
 * the user's edits saying where each stands and offering what can be done with it, and a build without a client id. The
 * sign-in page opens in a browser, never in Atlas, which answers openstreetmap.org links itself.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class OsmAccountScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val at = Instant.parse("2026-10-30T20:00:00Z")

    private fun edit(id: Long, state: EditQueue.State, label: String, way: Long = 266616341, changeset: Long? = null, reason: String? = null, trouble: EditQueue.Trouble? = null) =
        EditQueue.Queued(
            id, TagEdit(OsmType.WAY, way, mapOf("building:colour" to "#8A5A44"), mapOf("building:colour" to null)), label,
            52.37557, 4.88641, at, state, changeset = changeset, reason = reason, trouble = trouble,
        )

    private val edits = listOf(
        edit(1, EditQueue.State.SENT, "Leliegracht 49 · building:colour", changeset = 126),
        edit(2, EditQueue.State.CONFLICT, "Keizersgracht 123 · roof:shape", way = 4242),
        edit(3, EditQueue.State.REFUSED, "Prinsengracht 263 · building:colour", way = 4343, reason = "You must agree to the contributor terms before editing"),
        edit(4, EditQueue.State.PENDING, "Westermarkt 6 · building:colour", way = 4444, trouble = EditQueue.Trouble.BUSY),
    )

    private class Taps {
        val signIn = mutableListOf<Unit>()
        val signOut = mutableListOf<Unit>()
        val cancel = mutableListOf<Long>()
        val retry = mutableListOf<Long>()
        val lookAgain = mutableListOf<Long>()
        val sendNow = mutableListOf<Unit>()
    }

    /** What the board is shown with. */
    private data class Account(
        val user: String?,
        val edits: List<EditQueue.Queued> = emptyList(),
        val online: Boolean = true,
        val available: Boolean = true,
        val waiting: Boolean = false,
        val failure: OsmAccount.Failure? = null,
        val noBrowser: Boolean = false,
    )

    private val shown = mutableStateOf(Account(null))

    /** Shows the board for [account], set once a test and then changed through [shown]. */
    private fun show(account: Account): Taps {
        val taps = Taps()
        shown.value = account
        compose.setContent {
            val a = shown.value
            OsmAccountView(
                user = a.user, available = a.available, waiting = a.waiting, failure = a.failure, noBrowser = a.noBrowser, edits = a.edits,
                online = a.online, onBack = {}, onSignIn = { taps.signIn += Unit }, onSignOut = { taps.signOut += Unit },
                onCancel = { taps.cancel += it.id }, onRetry = { taps.retry += it.id }, onLookAgain = { taps.lookAgain += it.id },
                onSendNow = { taps.sendNow += Unit },
            )
        }
        compose.waitForIdle()
        return taps
    }

    private fun change(account: Account) {
        shown.value = account
        compose.waitForIdle()
    }

    private fun shot(name: String) = compose.onRoot().captureRoboImage(File(shotsDir, "mine_osm_$name.png").absolutePath)

    @Test
    fun signedOutTheEditsWaitAndSayWhy() {
        val taps = show(Account(null, listOf(edit(1, EditQueue.State.PENDING, "Leliegracht 49 · building:colour"))))
        compose.onNodeWithText("OpenStreetMap account").assertExists()
        compose.onNodeWithText("Not signed in").assertExists()
        compose.onNodeWithText("Edits you send are public on OpenStreetMap under your user name", substring = true).assertExists()
        compose.onNodeWithText("Waiting · sign in to send it").assertExists()
        compose.onNodeWithText("Send now").assertDoesNotExist()
        shot("signed_out")
        compose.onNodeWithText("Sign in with OpenStreetMap").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1, taps.signIn.size)
        assertEquals(listOf(1L), taps.cancel)
    }

    @Test
    fun signedInEachEditSaysWhereItStands() {
        val taps = show(Account("Erika", edits))
        compose.onNodeWithText("Erika").assertExists()
        compose.onNodeWithText("Signed in on openstreetmap.org").assertExists()
        compose.onNodeWithText("On OpenStreetMap · changeset 126").assertExists()
        compose.onNodeWithText("Not sent · the building changed on OpenStreetMap since you looked").assertExists()
        compose.onNodeWithText("Not sent · OpenStreetMap refused it: You must agree to the contributor terms before editing").assertExists()
        compose.onNodeWithText("Waiting · OpenStreetMap is busy, trying again").assertExists()
        shot("signed_in")
        compose.onNodeWithText("Try again").performClick()
        compose.onNodeWithText("Look again").performClick()
        compose.onNodeWithText("Send now").performClick()
        compose.onNodeWithText("Sign out").performClick()
        assertEquals(listOf(3L), taps.retry)
        assertEquals(listOf(2L), taps.lookAgain)
        assertEquals(1, taps.sendNow.size)
        assertEquals(1, taps.signOut.size)
    }

    @Test
    fun offlineAWaitingEditWaitsForTheNetwork() {
        show(Account("Erika", listOf(edit(1, EditQueue.State.PENDING, "Leliegracht 49 · building:colour")), online = false))
        compose.onNodeWithText("Waiting · sent once you are online").assertExists()
        compose.onNodeWithText("Send now").assertDoesNotExist()
    }

    @Test
    fun aSignInUnderWayOrFailedSaysSo() {
        show(Account(null, waiting = true))
        compose.onNodeWithText("Finish signing in in your browser: Atlas comes back by itself.").assertExists()
        shot("waiting")
        change(Account(null, failure = OsmAccount.Failure(declined = true, why = "access_denied")))
        compose.onNodeWithText("You did not let Atlas edit with your account.").assertExists()
        change(Account(null, failure = OsmAccount.Failure(declined = false, why = "OpenStreetMap could not be reached")))
        compose.onNodeWithText("Signing in did not finish: OpenStreetMap could not be reached").assertExists()
        change(Account(null, noBrowser = true))
        compose.onNodeWithText("No browser on this phone opens the sign-in page.").assertExists()
        // signed in, none of it shows
        change(Account("Erika", failure = OsmAccount.Failure(declined = true), noBrowser = true))
        compose.onNodeWithText("No browser on this phone opens the sign-in page.").assertDoesNotExist()
        compose.onNodeWithText("You did not let Atlas edit with your account.").assertDoesNotExist()
    }

    @Test
    fun withoutAClientIdThereIsNothingToSignInWith() {
        show(Account(null, available = false))
        compose.onNodeWithText("This build cannot sign in: it carries no OpenStreetMap client id.").assertExists()
        compose.onNodeWithText("Sign in with OpenStreetMap").assertDoesNotExist()
        compose.onNodeWithText("No edits yet.", substring = true).assertExists()
        shot("unavailable")
    }

    @Test
    fun thePlaceSheetsCardSaysWhereTheEditStands() {
        var opened = 0
        val q = edit(1, EditQueue.State.PENDING, "Leliegracht 49 · building:colour")
        compose.setContent { YourEditView(q, EditStatus.SIGNED_OUT) { opened++ } }
        compose.onNodeWithText("Your edit to OpenStreetMap").assertExists()
        compose.onNodeWithText("Waiting · sign in to send it").performClick()
        assertEquals(1, opened)
        shot("place_card")
    }

    @Test
    fun theSignInPageOpensInTheDefaultBrowserNeverInAtlas() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val page = "https://www.openstreetmap.org/oauth2/authorize?client_id=x&state=s"
        val app = shadowOf(context.packageManager)
        // no browser: nothing opens it
        assertFalse(openInBrowser(context, page))
        // Atlas answers openstreetmap.org links itself; a browser answers any page
        val browser = ComponentName("org.example.browser", "org.example.browser.Main")
        app.addActivityIfNotPresent(browser)
        app.addIntentFilterForActivity(browser, IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addCategory(Intent.CATEGORY_BROWSABLE)
            addDataScheme("https")
        })
        assertTrue(openInBrowser(context, page))
        val started = shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity
        assertEquals("org.example.browser", started.`package`)
        assertEquals(Uri.parse(page), started.data)
        assertNull(started.component)
    }
}
