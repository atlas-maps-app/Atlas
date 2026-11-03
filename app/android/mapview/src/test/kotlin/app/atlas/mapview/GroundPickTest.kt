package app.atlas.mapview

import app.atlas.geo.LatLon
import app.atlas.mapview.palette.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The core's line of a ground pick (`MapCore.pickGround`) as the map hands it to the screens: the water, a fill a slot
 * paints, one no slot paints, a quay's wall, the bare ground, or none.
 */
class GroundPickTest {
    @Test
    fun theCoresLineReadsBack() {
        assertEquals(
            GroundPick(GroundPick.What.WATER, Slot.WATER.code, LatLon(52.3745123, 4.8838456)),
            GroundPick.parse("water 16 52.3745123 4.8838456"),
        )
        assertEquals(
            GroundPick(GroundPick.What.WATER, Slot.WATER_DEEP.code, LatLon(52.0, 4.0)),
            GroundPick.parse("water 18 52.0000000 4.0000000"),
        )
        assertEquals(
            GroundPick(GroundPick.What.FILL, Slot.LAWN.code, LatLon(-33.9, 151.2)),
            GroundPick.parse("fill 2 -33.9000000 151.2000000"),
        )
        assertEquals(GroundPick(GroundPick.What.FILL, 0, LatLon(52.0, 4.0)), GroundPick.parse("fill 0 52.0000000 4.0000000"))
        assertEquals(
            GroundPick(GroundPick.What.QUAY, Slot.QUAY_WALLS.code, LatLon(52.0, 4.0)),
            GroundPick.parse("quay 19 52.0000000 4.0000000"),
        )
        assertEquals(
            GroundPick(GroundPick.What.PLATE, Slot.NIGHT_GROUND.code, LatLon(52.0, 4.0)),
            GroundPick.parse("plate 128 52.0000000 4.0000000"),
        )
    }

    @Test
    fun noneWhereTheCoreMetNone() {
        assertNull(GroundPick.parse(""))
        assertNull(GroundPick.parse("water 16 52.3"))
        assertNull(GroundPick.parse("lake 16 52.3 4.8"))
        assertNull(GroundPick.parse("water x 52.3 4.8"))
        assertNull(GroundPick.parse("water 16 52.3 4.8 more"))
    }
}
