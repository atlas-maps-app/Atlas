package app.atlas.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Against the Open Location Code project's own test data. */
class PlusCodeTest {
    @Test fun encodingCsvFromDegrees() {
        val rows = Fixtures.csv("olc/encoding.csv")
        assertTrue(rows.size > 300)
        // The reference suites allow 5% floating-point misses when encoding from degrees; ours has none.
        for (r in rows) assertEquals("${r[0]}, ${r[1]}", r[5], PlusCode.encode(r[0].toDouble(), r[1].toDouble(), r[4].toInt()))
    }

    @Test fun encodingCsvIntegersAreExact() {
        for (r in Fixtures.csv("olc/encoding.csv")) {
            val ints = PlusCode.degreesToIntegers(r[0].toDouble(), r[1].toDouble())
            assertEquals("lat ${r[0]}", r[2].toLong(), ints[0])
            assertEquals("lon ${r[1]}", r[3].toLong(), ints[1])
            assertEquals(r[5], PlusCode.encodeIntegers(r[2].toLong(), r[3].toLong(), r[4].toInt()))
        }
    }

    @Test fun decodingCsv() {
        val rows = Fixtures.csv("olc/decoding.csv")
        assertTrue(rows.size > 300)
        for (r in rows) {
            val a = PlusCode.decode(r[0])
            assertEquals(r[0], r[1].toInt(), a.length)
            assertEquals(r[0], r[2].toDouble(), a.south, 1e-10)
            assertEquals(r[0], r[3].toDouble(), a.west, 1e-10)
            assertEquals(r[0], r[4].toDouble(), a.north, 1e-10)
            assertEquals(r[0], r[5].toDouble(), a.east, 1e-10)
        }
    }

    @Test fun shortCodeCsv() {
        for (r in Fixtures.csv("olc/shortCodeTests.csv")) {
            val full = r[0]
            val lat = r[1].toDouble()
            val lon = r[2].toDouble()
            val short = r[3]
            val type = r[4]
            if (type == "B" || type == "S") assertEquals("shorten $full", short, PlusCode.shorten(full, lat, lon))
            if (type == "B" || type == "R") assertEquals("recover $short", full, PlusCode.recoverNearest(short, lat, lon))
        }
    }

    @Test fun validityCsv() {
        for (r in Fixtures.csv("olc/validityTests.csv")) {
            val code = r[0]
            assertEquals("valid $code", r[1].toBoolean(), PlusCode.isValid(code))
            assertEquals("short $code", r[2].toBoolean(), PlusCode.isShort(code))
            assertEquals("full $code", r[3].toBoolean(), PlusCode.isFull(code))
        }
    }

    @Test fun theWesterkerk() {
        val code = PlusCode.encode(Fixtures.westerkerk)
        assertEquals("9F469VFM+RG", code)
        val area = PlusCode.decode(code)
        assertTrue(Fixtures.westerkerk in area.bbox)
        val short = PlusCode.shorten(code, 52.37, 4.89)
        assertEquals("FM+RG", short)
        assertEquals(code, PlusCode.recoverNearest(short, 52.36, 4.9))
    }
}
