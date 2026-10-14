package app.atlas.providers

import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.util.Base64

class MangroveWriterTest {
    private val key = LocalReviewerKey.generate()
    private val now = Instant.parse("2026-10-13T18:30:00Z")
    private val wester = Place(OsmId(OsmType.NODE, 2720971344), LatLon(52.3760284, 4.8838771), mapOf("amenity" to "cafe", "name" to "Café Wester"))

    // The writer talks to a local server only: tests never send a review to Mangrove.
    private fun writer(base: String = "http://127.0.0.1:9") = MangroveWriter(testHttp(), key, clientId = "https://atlas-maps-app.github.io", base = base) { now }

    @Test
    fun draftsASignedReviewThatVerifies() {
        val review = writer().draft(wester, 4.0, "Apple pie by the church.", Service.EATEN_THERE, nickname = "erika")
        assertTrue(MangroveWriter.verify(review.jwt))
        val p = review.payload
        assertEquals("geo:52.3760284,4.8838771?q=Caf%C3%A9%20Wester&u=30", p.str("sub"))
        assertEquals(75, p.int("rating"))
        assertEquals("Apple pie by the church.", p.str("opinion"))
        assertEquals(now.epochSecond, p.long("iat"))
        val meta = p.obj("metadata")!!
        assertEquals("CC-BY-4.0", meta.str("license"))
        assertEquals("node/2720971344", meta.str("osm_id"))
        assertEquals("eaten_there", meta.str("atlas_how"))
        assertEquals(true, meta.bool("is_personal_experience"))
        assertEquals("https://atlas-maps-app.github.io", meta.str("client_id"))
    }

    @Test
    fun headerCarriesTheKeyAsMangroveExpects() {
        val jwt = writer().draft(wester, 5.0).jwt
        val header = ProviderJson.parseToJsonElement(String(Base64.getUrlDecoder().decode(jwt.substringBefore('.')))).asObject()!!
        assertEquals("ES256", header.str("alg"))
        val kid = header.str("kid")!!
        assertTrue(kid.startsWith("-----BEGIN PUBLIC KEY-----MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE"))
        assertFalse(kid.contains('\n'))
        val jwk = ProviderJson.parseToJsonElement(header.str("jwk")!!).asObject()!!
        assertEquals("P-256", jwk.str("crv"))
        assertEquals(43, jwk.str("x")!!.length)
        // A raw ES256 signature is 64 bytes: r and s.
        assertEquals(64, Base64.getUrlDecoder().decode(jwt.substringAfterLast('.')).size)
    }

    @Test
    fun theSameKeyIsTheSameReviewer() {
        val restored = LocalReviewerKey.of(key.privateKeyPkcs8(), key.publicKeyDer())
        val a = MangroveWriter(testHttp(), key) { now }.draft(wester, 3.0).jwt
        val b = MangroveWriter(testHttp(), restored) { now }.draft(wester, 3.0).jwt
        assertEquals(a.substringBefore('.'), b.substringBefore('.'))
        assertTrue(MangroveWriter.verify(b))
    }

    @Test
    fun rejectsWhatMangroveWouldRefuse() {
        for (bad in listOf(0.5, 5.5)) {
            try {
                writer().draft(wester, bad)
                fail("accepted $bad stars")
            } catch (_: MangroveException) {
            }
        }
        try {
            writer().draft(wester, 4.0, "x".repeat(501))
            fail("accepted 501 characters")
        } catch (_: MangroveException) {
        }
        try {
            writer().draft(Place(null, LatLon(0.0, 0.0), emptyMap()), 4.0)
            fail("accepted a place with no name")
        } catch (_: MangroveException) {
        }
    }

    @Test
    fun derAndRawSignaturesConvertBothWays() {
        repeat(50) {
            val der = key.sign("message $it".toByteArray())
            val raw = Es256.derToRaw(der)
            assertEquals(64, raw.size)
            assertTrue(Es256.rawToDer(raw).contentEquals(der))
        }
    }

    @Test
    fun submitPutsTheJwtInThePath() = runBlocking {
        Mock().use { m ->
            m.enqueue("true")
            val w = writer(m.base)
            val review = w.draft(wester, 4.0)
            assertTrue(w.submit(review))
            val req = m.take()
            assertEquals("PUT", req.method)
            assertEquals("/submit/${review.jwt}", req.url.encodedPath)
        }
    }

    @Test
    fun aRefusedReviewSaysWhy() = runBlocking {
        Mock().use { m ->
            m.enqueue("Invalid JWT: rating must be between 0 and 100", 400)
            val w = writer(m.base)
            try {
                w.submit(w.draft(wester, 4.0))
                fail()
            } catch (e: MangroveException) {
                assertTrue(e.message!!.contains("HTTP 400"))
            }
        }
    }
}
