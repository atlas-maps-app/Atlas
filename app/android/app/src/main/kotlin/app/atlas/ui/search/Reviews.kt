package app.atlas.ui.search

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.atlas.Services
import app.atlas.model.Place
import app.atlas.providers.LocalReviewerKey
import app.atlas.providers.MangroveWriter
import app.atlas.providers.ReviewerKey
import app.atlas.providers.Service
import app.atlas.providers.SignedReview
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * The phone's Mangrove reviewer: one ECDSA P-256 key, made on first use and kept, so every review this phone writes
 * is signed by the same reviewer (there is no account). The key lives in the Android Keystore when the phone has one;
 * otherwise (and in tests) in a private file.
 */
object Reviewer {
    private const val ALIAS = "atlas.mangrove.reviewer"

    @Volatile
    private var key: ReviewerKey? = null

    /** The reviewer key, made the first time it is needed. */
    @Synchronized
    fun key(services: Services): ReviewerKey = key ?: (keystoreKey() ?: fileKey(File(services.dirs.files, "search/reviewer.key"))).also { key = it }

    /** A signed review of [place], ready to show and send; nothing is sent. */
    fun draft(services: Services, place: Place, stars: Int, opinion: String?, service: Service?): SignedReview =
        MangroveWriter(services.http, key(services), clientId = "atlas").draft(place, stars.toDouble(), opinion?.takeIf { it.isNotBlank() }, service)

    /** Publishes [review] on Mangrove: public, under CC BY 4.0, once this returns. */
    suspend fun send(services: Services, review: SignedReview): Boolean = MangroveWriter(services.http, key(services), clientId = "atlas").submit(review)

    private fun keystoreKey(): ReviewerKey? = runCatching {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .build(),
                )
            }.generateKeyPair()
        }
        val entry = ks.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
        KeystoreKey(entry.privateKey, entry.certificate.publicKey.encoded)
    }.getOrNull()

    /** A key kept in [file] as two Base64 lines (PKCS#8, then X.509); made when the file is missing or broken. */
    internal fun fileKey(file: File): ReviewerKey {
        runCatching {
            val (priv, pub) = file.readLines().map { Base64.getDecoder().decode(it.trim()) }
            return LocalReviewerKey.of(priv, pub)
        }
        val k = LocalReviewerKey.generate()
        file.parentFile?.mkdirs()
        file.writeText(Base64.getEncoder().encodeToString(k.privateKeyPkcs8()) + "\n" + Base64.getEncoder().encodeToString(k.publicKeyDer()) + "\n")
        return k
    }

    private class KeystoreKey(private val private: PrivateKey, private val public: ByteArray) : ReviewerKey {
        override fun publicKeyDer(): ByteArray = public
        override fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
            initSign(private)
            update(data)
            sign()
        }
    }
}
