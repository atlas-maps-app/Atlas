package app.atlas.services

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.atlas.providers.OsmAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.net.URI
import java.net.URLDecoder
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The user's OpenStreetMap account, for the edits they make (FillIn, the painter). They sign in on openstreetmap.org in
 * the browser with OAuth 2.0 and PKCE, and the redirect back to the app finishes it. The token is sealed with a key the
 * platform keeps ([Sealer]: the Android Keystore on a phone, so the key never leaves it), and the user's name is kept to
 * show. Without [signIn] (the app has no client id until the user registers one on openstreetmap.org) it is switched
 * off: [available] is false and nothing is asked.
 */
class OsmAccount(
    private val store: DataStore<Preferences>,
    private val sealer: Sealer,
    private val signIn: SignIn?,
    scope: CoroutineScope,
) {
    /** What signing in needs from OpenStreetMap: the page to open, then the code's exchange and the user's name. */
    interface SignIn {
        val redirectUri: String

        fun page(challenge: String, state: String): String

        suspend fun exchange(code: String, verifier: String): String

        suspend fun userName(token: String): String
    }

    /** OsmAuth with the app's client id, as a [SignIn]. */
    class Osm(private val auth: OsmAuth, override val redirectUri: String) : SignIn {
        override fun page(challenge: String, state: String) = auth.authorizeUrl(challenge, state)

        override suspend fun exchange(code: String, verifier: String) = auth.exchange(code, verifier)

        override suspend fun userName(token: String) = auth.userName(token)
    }

    /** Whether the app can sign in at all. */
    val available: Boolean get() = signIn != null

    /** The signed-in user's name, null while signed out (or before the file was read). */
    val user: StateFlow<String?> = store.data.map { it[USER] }.stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * Starts a sign-in: the page to open in the browser, its verifier and state kept until the redirect comes back.
     * Null when the app cannot sign in.
     */
    suspend fun begin(): String? {
        val s = signIn ?: return null
        val verifier = OsmAuth.verifier()
        val state = OsmAuth.verifier().take(STATE_LENGTH)
        store.edit {
            it[VERIFIER] = verifier
            it[STATE] = state
        }
        return s.page(OsmAuth.challenge(verifier), state)
    }

    /**
     * Finishes the sign-in that the redirect [uri] answers. False where the redirect is not this app's, its state is not
     * the one [begin] kept, or the user declined (no code); a declined or foreign answer ends the pending sign-in.
     */
    suspend fun finish(uri: String): Boolean {
        val s = signIn ?: return false
        if (uri != s.redirectUri && !uri.startsWith(s.redirectUri + "?")) return false
        val query = query(uri)
        val kept = store.data.first()
        val verifier = kept[VERIFIER] ?: return false
        val code = query["code"]
        if (query["state"] != kept[STATE] || code == null) {
            store.edit {
                it.remove(VERIFIER)
                it.remove(STATE)
            }
            return false
        }
        val token = s.exchange(code, verifier)
        val name = s.userName(token)
        val sealed = Base64.getEncoder().encodeToString(sealer.seal(token.toByteArray(Charsets.UTF_8)))
        store.edit {
            it.remove(VERIFIER)
            it.remove(STATE)
            it[TOKEN] = sealed
            it[USER] = name
        }
        return true
    }

    /** The token edits are sent with, or null while signed out (or where the platform's key is gone). */
    suspend fun token(): String? {
        val sealed = store.data.first()[TOKEN] ?: return null
        return runCatching { String(sealer.open(Base64.getDecoder().decode(sealed)), Charsets.UTF_8) }.getOrNull()
    }

    /** Signs out on this phone: the token and the name are forgotten. */
    suspend fun signOut() {
        store.edit {
            it.remove(TOKEN)
            it.remove(USER)
        }
    }

    companion object {
        /** The redirect the app answers (a private-use scheme, RFC 8252, from the app's id). */
        const val REDIRECT = "app.atlas:/osm-signin"
        private const val STATE_LENGTH = 16
        private val VERIFIER = stringPreferencesKey("osm_verifier")
        private val STATE = stringPreferencesKey("osm_state")
        private val TOKEN = stringPreferencesKey("osm_token")
        private val USER = stringPreferencesKey("osm_user")

        /** The query of [uri] as names and decoded values. */
        internal fun query(uri: String): Map<String, String> {
            val raw = runCatching { URI(uri).rawQuery }.getOrNull() ?: return emptyMap()
            return raw.split('&').filter { it.isNotEmpty() }.associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            }
        }
    }
}

/** Seals and opens small secrets with a key the platform keeps. */
interface Sealer {
    fun seal(plain: ByteArray): ByteArray

    fun open(sealed: ByteArray): ByteArray
}

/**
 * AES-GCM with a 256-bit key made in and kept by the Android Keystore under [alias]: the key never leaves it, so a copy
 * of the app's files does not carry the token. A sealed value is the nonce (12 bytes), then the ciphertext and its tag.
 */
class KeystoreSealer(private val alias: String = "atlas-osm-token") : Sealer {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(STORE).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE).apply { init(spec) }.generateKey()
    }

    override fun seal(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.ENCRYPT_MODE, key())
        return c.iv + c.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, NONCE))
        return c.doFinal(sealed, NONCE, sealed.size - NONCE)
    }

    private companion object {
        const val STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val NONCE = 12
        const val TAG_BITS = 128
    }
}
