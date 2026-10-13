package app.atlas.providers

import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.Place
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64

/**
 * The reviewer's signing key: an ECDSA P-256 key pair kept by the app (the Android Keystore on a phone).
 * The same key is the same reviewer; there is no account.
 */
interface ReviewerKey {
    /** The public key as X.509 SubjectPublicKeyInfo DER (what `PublicKey.getEncoded()` returns). */
    fun publicKeyDer(): ByteArray

    /** A SHA-256 ECDSA signature of [data] in DER form, as `Signature.getInstance("SHA256withECDSA")` makes it. */
    fun sign(data: ByteArray): ByteArray
}

/** A [ReviewerKey] held in memory, for computers and tests; the app wraps a Keystore key instead. */
class LocalReviewerKey(private val pair: KeyPair) : ReviewerKey {
    override fun publicKeyDer(): ByteArray = pair.public.encoded

    override fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(pair.private)
        update(data)
        sign()
    }

    /** The private key as PKCS#8 DER, to store it. */
    fun privateKeyPkcs8(): ByteArray = pair.private.encoded

    companion object {
        fun generate(): LocalReviewerKey = LocalReviewerKey(KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair())

        /** Restores a key from its PKCS#8 private and X.509 public encodings. */
        fun of(pkcs8: ByteArray, x509: ByteArray): LocalReviewerKey {
            val kf = KeyFactory.getInstance("EC")
            return LocalReviewerKey(KeyPair(kf.generatePublic(X509EncodedKeySpec(x509)), kf.generatePrivate(PKCS8EncodedKeySpec(pkcs8))))
        }
    }
}

/** A signed review ready to send: the JWT, and the payload it carries. Nothing has been sent yet. */
data class SignedReview(val jwt: String, val payload: JsonObject) {
    val subject: String get() = payload.str("sub").orEmpty()
}

/** A review could not be built or was refused. */
class MangroveException(message: String) : ProviderException(Mangrove.ID, message)

/**
 * Writes Mangrove reviews as `reviews/mangrove.py` does: an ES256 JWT whose header names the writer's public key
 * (`kid` as one-line PEM, plus `jwk`), and whose payload is the subject, the 0–100 rating, the opinion, the time and
 * the metadata (licence CC BY 4.0, OSM id, and for food Atlas's "eaten there, takeaway or delivered").
 *
 * [draft] only builds and signs. [submit] publishes: a review is public, under CC BY 4.0, once Mangrove accepts it.
 */
class MangroveWriter(
    private val http: Http,
    private val key: ReviewerKey,
    private val clientId: String? = null,
    private val base: String = "https://api.mangrove.reviews",
    private val now: () -> Instant = Instant::now,
) {
    /** A signed review of [place]; see the other [draft] for the fields. */
    fun draft(place: Place, stars: Double, opinion: String? = null, service: Service? = null, nickname: String? = null, personal: Boolean = true): SignedReview {
        val name = place.name() ?: throw MangroveException("A Mangrove review needs the place's name.")
        return draft(name, place.location, stars, opinion, place.id, service, nickname, personal)
    }

    /**
     * A signed review of the place named [name] at [at]. [stars] go 1 to 5; [opinion] holds at most 500 characters;
     * [service] says how the food was had, for places to eat and drink. Nothing is sent.
     */
    fun draft(
        name: String,
        at: LatLon,
        stars: Double,
        opinion: String? = null,
        osmId: OsmId? = null,
        service: Service? = null,
        nickname: String? = null,
        personal: Boolean = true,
        uncertainty: Int = GeoSubject.UNCERTAINTY,
    ): SignedReview {
        if (stars !in 1.0..5.0) throw MangroveException("Stars go from 1 to 5.")
        val text = opinion?.trim()?.takeIf { it.isNotEmpty() }
        if (text != null && text.length > MAX_OPINION) throw MangroveException("A Mangrove review holds at most $MAX_OPINION characters; this one has ${text.length}.")
        val payload = buildJsonObject {
            put("sub", GeoSubject(at, name, uncertainty).toString())
            put("rating", Mangrove.ratingOf(stars))
            text?.let { put("opinion", it) }
            put("iat", now().epochSecond)
            put("metadata", buildJsonObject {
                put("license", Mangrove.LICENCE)
                if (personal) put("is_personal_experience", true)
                clientId?.let { put("client_id", it) }
                nickname?.takeIf { it.isNotBlank() }?.let { put("nickname", it.trim()) }
                osmId?.let { put("osm_id", "${it.type.name.lowercase()}/${it.id}") }
                service?.let { put("atlas_how", it.value) }
            })
        }
        val der = key.publicKeyDer()
        val header = buildJsonObject {
            put("alg", "ES256")
            put("typ", "JWT")
            put("kid", pem(der))
            put("jwk", jwk(der))
        }
        val signingInput = b64url(header.toString().toByteArray()) + "." + b64url(payload.toString().toByteArray(Charsets.UTF_8))
        val signature = Es256.derToRaw(key.sign(signingInput.toByteArray(Charsets.US_ASCII)))
        return SignedReview("$signingInput.${b64url(signature)}", payload)
    }

    /** Publishes [review]. It is public, under CC BY 4.0, as soon as Mangrove accepts it; true on acceptance. */
    suspend fun submit(review: SignedReview): Boolean {
        val request = http.request("$base/submit/${review.jwt}").put(ByteArray(0).toRequestBody()).build()
        val res = http.execute(request)
        if (!res.ok) throw MangroveException("Mangrove refused the review (HTTP ${res.code}): ${res.text.take(300)}")
        return true
    }

    companion object {
        const val MAX_OPINION = 500

        internal fun b64url(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
        private fun unb64url(s: String): ByteArray = Base64.getUrlDecoder().decode(s.trimEnd('='))

        /** The public key as Mangrove's `kid`: PEM on one line, no line breaks inside the Base64. */
        fun pem(x509: ByteArray): String = "-----BEGIN PUBLIC KEY-----" + Base64.getEncoder().encodeToString(x509) + "-----END PUBLIC KEY-----"

        /** The public key as a JWK string, the way Mangrove's web client writes it. */
        fun jwk(x509: ByteArray): String {
            val point = x509.copyOfRange(x509.size - 65, x509.size)
            require(point[0] == 0x04.toByte()) { "Not an uncompressed P-256 public key" }
            return buildJsonObject {
                put("crv", "P-256")
                put("ext", true)
                put("key_ops", JsonArray(listOf(JsonPrimitive("verify"))))
                put("kty", "EC")
                put("x", b64url(point.copyOfRange(1, 33)))
                put("y", b64url(point.copyOfRange(33, 65)))
            }.toString()
        }

        /** True when [jwt]'s ES256 signature matches the key its header names. Works for anyone's review. */
        fun verify(jwt: String): Boolean = runCatching {
            val parts = jwt.split('.')
            if (parts.size != 3) return false
            val header = ProviderJson.parseToJsonElement(String(unb64url(parts[0]), Charsets.UTF_8)).asObject() ?: return false
            if (header.str("alg") != "ES256") return false
            val kid = header.str("kid") ?: return false
            val der = Base64.getMimeDecoder().decode(kid.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "").trim())
            val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(pub)
                update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
                verify(Es256.rawToDer(unb64url(parts[2])))
            }
        }.getOrDefault(false)

        /** The payload of [jwt] without checking it. */
        fun payloadOf(jwt: String): JsonObject? = runCatching {
            ProviderJson.parseToJsonElement(String(unb64url(jwt.split('.')[1]), Charsets.UTF_8)).asObject()
        }.getOrNull()
    }
}

/** ES256 signatures: Java writes ECDSA signatures as DER; a JWT carries r and s side by side, 32 bytes each. */
object Es256 {
    fun derToRaw(der: ByteArray): ByteArray {
        if (der.isEmpty() || der[0] != 0x30.toByte()) throw MangroveException("Not an ECDSA signature")
        var i = 2
        if (der[1].toInt() and 0x80 != 0) i += der[1].toInt() and 0x7f // long-form length, never for P-256 but harmless
        val out = ByteArray(64)
        for (half in 0..1) {
            if (der[i] != 0x02.toByte()) throw MangroveException("Not an ECDSA signature")
            val n = der[i + 1].toInt()
            var start = i + 2
            var len = n
            while (len > 32 && der[start] == 0.toByte()) {
                start++
                len--
            }
            if (len > 32) throw MangroveException("Signature integer too long")
            System.arraycopy(der, start, out, half * 32 + 32 - len, len)
            i += 2 + n
        }
        return out
    }

    fun rawToDer(raw: ByteArray): ByteArray {
        require(raw.size == 64) { "An ES256 signature has 64 bytes" }
        fun integer(b: ByteArray): ByteArray {
            var s = 0
            while (s < b.size - 1 && b[s] == 0.toByte()) s++
            val trimmed = b.copyOfRange(s, b.size)
            val body = if (trimmed[0].toInt() and 0x80 != 0) byteArrayOf(0) + trimmed else trimmed
            return byteArrayOf(0x02, body.size.toByte()) + body
        }
        val body = integer(raw.copyOfRange(0, 32)) + integer(raw.copyOfRange(32, 64))
        return byteArrayOf(0x30, body.size.toByte()) + body
    }
}
