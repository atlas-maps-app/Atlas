package app.atlas.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OsmIdTest {
    @Test
    fun readsTheShortTheLongAndTheLinkForms() {
        assertEquals(OsmId(OsmType.NODE, 123), OsmId.parse("n123"))
        assertEquals(OsmId(OsmType.WAY, 45), OsmId.parse("way/45"))
        assertEquals(OsmId(OsmType.RELATION, 6), OsmId.parse("relation 6"))
        assertEquals(OsmId(OsmType.NODE, 13_000_000_000), OsmId.parse("https://www.openstreetmap.org/node/13000000000"))
        assertEquals(OsmId(OsmType.NODE, Long.MAX_VALUE), OsmId.parse("n9223372036854775807"))
    }

    @Test
    fun aNumberTooLargeForAnIdIsNoId() {
        // parse threw NumberFormatException here, out of the app's link handling and the Query screen
        assertNull(OsmId.parse("n9223372036854775808"))
        assertNull(OsmId.parse("node/99999999999999999999"))
        assertNull(OsmId.parse("https://www.openstreetmap.org/way/123456789012345678901234567890"))
        // a review's metadata, "<type>/<id>/<version>"
        assertNull(OsmId.parse("node/99999999999999999999/1"))
    }
}
