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

        override fun page(challenge: String, state: String) =
            "https://www.openstreetmap.org/oauth2/authorize?code_challenge=$challenge&state=$state"

        override suspend fun exchange(code: String, verifier: String): String {
            this.code = code
            this.verifier = verifier
            return "tok-1"
        }

        override suspend fun userName(token: String) = if (token == "tok-1") "Erika" else error("unknown token")
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
    fun signingOutForgetsTheTokenAndTheName() = runBlocking {
        val a = account(FakeOsm())
        val state = a.begin()!!.toHttpUrl().queryParameter("state")!!
        assertTrue(a.finish("${OsmAccount.REDIRECT}?code=c&state=$state"))
        a.signOut()
        assertNull(a.token())
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
