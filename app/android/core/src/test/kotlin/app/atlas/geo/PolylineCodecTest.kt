package app.atlas.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PolylineCodecTest {
    private val sample = listOf(LatLon(38.5, -120.2), LatLon(40.7, -120.95), LatLon(43.252, -126.453))

    @Test fun encodesTheSpecificationExample() {
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", PolylineCodec.encode(sample))
        assertEquals(sample, PolylineCodec.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@"))
    }

    @Test fun precisionSixRoundTripsARealStreet() {
        val street = Fixtures.ams.filter { it.type == "way" && it.tags["highway"] != null }.maxBy { it.points.size }.points
        for (precision in listOf(5, 6)) {
            val back = PolylineCodec.decode(PolylineCodec.encode(street, precision), precision)
            assertEquals(street.size, back.size)
            val tolerance = if (precision == 5) 0.7 else 0.07
            for (i in street.indices) assertEquals(0.0, Geo.distance(street[i], back[i]), tolerance)
        }
        // Deltas are of rounded integers, so long lines do not drift.
        val long = List(5000) { LatLon(52.0 + it * 1.234567e-5, 4.0 - it * 7.654321e-6) }
        val back = PolylineCodec.decode(PolylineCodec.encode(long, 6), 6)
        assertEquals(long.last().lat, back.last().lat, 5e-7)
        assertEquals(long.last().lon, back.last().lon, 5e-7)
    }

    @Test fun malformedInputIsRejected() {
        assertNull(PolylineCodec.decodeOrNull("_p~iF~ps|U_ulL"))   // a latitude without its longitude
        assertNull(PolylineCodec.decodeOrNull("_p~iF~ps|U_ulLnnqC_mqNvxq`"))   // truncated mid-value
        assertNull(PolylineCodec.decodeOrNull("abc def"))
        assertEquals(emptyList<LatLon>(), PolylineCodec.decode(""))
        assertEquals("??", PolylineCodec.encode(listOf(LatLon(0.0, 0.0))))
    }
}
