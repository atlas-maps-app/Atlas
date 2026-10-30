package app.atlas.services

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.atlas.providers.OsmAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class OsmAccountTest {
    @get:Rule
    val dir = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun stop() = scope.cancel()

    /** A sealer for the JVM: reversible, and visibly not the plain text. */
    private val sealer = object : Sealer {
        override fun seal(plain: ByteArray) = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()

        override fun open(sealed: ByteArray) = seal(sealed)
    }

    /** OpenStreetMap's side, recording what it was asked. */
    private class FakeOsm : OsmAccount.SignIn {
        override val redirectUri = OsmAccount.REDIRECT
        var verifier: String? = null
        var code: String? = null
        var revoked: String? = null
        var exchangeFails = false
        var revokeFails = false

        override fun page(challenge: String, state: String) =
            "https://www.openstreetmap.org/oauth2/authorize?code_challenge=$challenge&state=$state"

        override suspend fun exchange(code: String, verifier: String): String {
            if (exchangeFails) throw IOException("OpenStreetMap could not be reached")
            this.code = code
            this.verifier = verifier
            return "tok-1"
        }

        override suspend fun userName(token: String) = if (token == "tok-1") "Erika" else error("unknown token")

        override suspend fun revoke(token: String): Boolean {
            if (revokeFails) throw IOException("offline")
            revoked = token
            return true
        }
    }

    private suspend fun signedIn(a: OsmAccount) {
        val state = a.begin()!!.toHttpUrl().queryParameter("state")!!
        assertTrue(a.finish("${OsmAccount.REDIRECT}?code=c&state=$state"))
    }

    private fun account(osm: OsmAccount.SignIn?, name: String = "osm.preferences_pb") =
        OsmAccount(PreferenceDataStoreFactory.create(scope = scope) { File(dir.root, name) }, sealer, osm, scope)

    @Test
    fun aSignInFinishesWithTheVerifierItsPageWasAskedFor() = runBlocking {
        val osm = FakeOsm()
        val a = account(osm)
        assertTrue(a.available)
        val page = a.begin()!!.toHttpUrl()
        val state = page.queryParameter("state")!!
        assertTrue(a.finish("${OsmAccount.REDIRECT}?code=code-1&state=$state"))
        assertEquals("code-1", osm.code)
        // the verifier sent proves the challenge the page carried
        assertEquals(page.queryParameter("code_challenge"), OsmAuth.challenge(osm.verifier!!))
        assertEquals("tok-1", a.token())
        assertEquals("Erika", a.user.filterNotNull().first())
        // the token is kept sealed, never as its text
        val kept = File(dir.root, "osm.preferences_pb").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(kept.contains("tok-1"))
    }

    @Test
    fun aForeignOrDeclinedRedirectSignsNothingIn() = runBlocking {
        val a = account(FakeOsm())
        val state = a.begin()!!.toHttpUrl().queryParameter("state")!!
        assertFalse(a.finish("https://example.com/osm-signin?code=x&state=$state"))
        assertFalse(a.finish("${OsmAccount.REDIRECT}X?code=x&state=$state"))
        // a state that is not ours ends the pending sign-in
        assertFalse(a.finish("${OsmAccount.REDIRECT}?code=x&state=not-$state"))
        assertFalse(a.finish("${OsmAccount.REDIRECT}?code=x&state=$state"))
        // the user declined: no code
        val again = a.begin()!!.toHttpUrl().queryParameter("state")!!
        assertFalse(a.finish("${OsmAccount.REDIRECT}?error=access_denied&state=$again"))
        assertNull(a.token())
    }

    @Test
    fun signingOutForgetsTheTokenAndWithdrawsIt() = runBlocking {
        val osm = FakeOsm()
        val a = account(osm)
        signedIn(a)
        a.signOut()
        assertNull(a.token())
        assertEquals("tok-1", osm.revoked)
        // out of reach, it is forgotten here all the same
        signedIn(a)
        osm.revokeFails = true
        a.signOut()
        assertNull(a.token())
        // a sign-in OpenStreetMap no longer accepts is forgotten without asking it
        osm.revokeFails = false
        osm.revoked = null
        signedIn(a)
        a.signOut(withdraw = false)
        assertNull(a.token())
        assertNull(osm.revoked)
    }

    @Test
    fun aSignInThatDoesNotFinishSaysWhy() = runBlocking {
        val osm = FakeOsm()
        val a = account(osm)
        var state = a.begin()!!.toHttpUrl().queryParameter("state")!!
        withTimeout(5_000) { a.waiting.first { it } }
        assertFalse(a.finish("${OsmAccount.REDIRECT}?error=access_denied&error_description=The+resource+owner+denied&state=$state"))
        assertEquals(OsmAccount.Failure(declined = true, why = "The resource owner denied"), a.failure.value)
        withTimeout(5_000) { a.waiting.first { w -> !w } }
        // a new sign-in clears it; OpenStreetMap out of reach for the token says so
        state = a.begin()!!.toHttpUrl().queryParameter("state")!!
        assertNull(a.failure.value)
        osm.exchangeFails = true
        assertFalse(a.finish("${OsmAccount.REDIRECT}?code=c&state=$state"))
        assertEquals(OsmAccount.Failure(declined = false, why = "OpenStreetMap could not be reached"), a.failure.value)
        assertNull(a.token())
    }

    @Test
    fun theRedirectIsTheAccountsOnlyWithItsState() = runBlocking {
        val a = account(FakeOsm())
        assertFalse("no sign-in under way", a.awaits("${OsmAccount.REDIRECT}?code=c&state=s"))
        val state = a.begin()!!.toHttpUrl().queryParameter("state")!!
        assertTrue(a.awaits("${OsmAccount.REDIRECT}?code=c&state=$state"))
        assertFalse(a.awaits("${OsmAccount.REDIRECT}?code=c&state=not-$state"))
        assertFalse(a.awaits("https://example.com/osm-signin?code=c&state=$state"))
        // the test server's account has a sign-in of its own
        val dev = account(FakeOsm(), "osm-dev.preferences_pb")
        assertFalse(dev.awaits("${OsmAccount.REDIRECT}?code=c&state=$state"))
    }

    @Test
    fun withoutAClientIdNothingIsAsked() = runBlocking {
        val a = account(null)
        assertFalse(a.available)
        assertNull(a.begin())
        assertFalse(a.finish("${OsmAccount.REDIRECT}?code=c&state=s"))
    }

    @Test
    fun aRedirectsQueryIsDecoded() {
        assertEquals(mapOf("code" to "a b", "state" to "x/y"), OsmAccount.query("${OsmAccount.REDIRECT}?code=a+b&state=x%2Fy"))
    }
}
