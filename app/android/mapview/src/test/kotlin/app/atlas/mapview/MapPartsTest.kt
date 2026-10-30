package app.atlas.mapview

import app.atlas.mapcore.state.CameraWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The map's parts (MapParts.kt): a bit each of the camera state's parts, every one once; the defaults (every part
 * drawn, the clouds' shadows and the water's light again since their rework); and the group switches.
 */
class MapPartsTest {
    @Test
    fun everyPartIsABitOfItsOwnAndEveryBitAPart() {
        assertEquals(CameraWriter.PARTS, MapPart.entries.size)
        assertEquals((1L shl CameraWriter.PARTS) - 1, MapParts.bits(MapPart.entries.toSet()))
        assertEquals(1L shl CameraWriter.PART_RAIN, MapPart.RAIN.bit)
        assertEquals(1L shl CameraWriter.PART_FROSTED, MapPart.FROSTED.bit)
        // listed once each, under the group it is named after
        assertEquals(MapPart.entries.toSet(), MapPartGroup.entries.flatMap { it.listed }.toSet())
        assertEquals(MapPart.entries.size, MapPartGroup.entries.sumOf { it.listed.size })
    }

    @Test
    fun everyPartIsDrawnByDefaultTheCloudsShadowsAndTheWatersLightAmongThem() {
        assertEquals(emptySet<MapPart>(), MapParts.byDefault)
        assertTrue(MapPart.entries.all { it.drawn })
        assertEquals(
            setOf(MapPart.REFLECTIONS, MapPart.RIPPLE, MapPart.GLITTER, MapPart.RINGS, MapPart.GLINTS, MapPart.WATER_STREAKS),
            MapPartGroup.WATER_LIGHT.parts,
        )
    }

    @Test
    fun aGroupsSwitchTurnsAllOfItOffWhileAnyIsDrawnAndAllOfItOnWhenNoneIs() {
        val weather = MapPartGroup.WEATHER.parts
        assertTrue(MapPart.RAIN in weather && MapPart.CLOUD_SHADOWS in weather && MapPart.RINGS in weather)
        assertTrue(MapPart.WET_CORES in weather && MapPart.LAMP_STREAKS in weather)
        // from the defaults one tap leaves all of the weather out, the next takes it all in again
        val off = MapParts.toggled(MapParts.byDefault, MapPartGroup.WEATHER)
        assertEquals(weather, off)
        assertEquals(MapParts.byDefault, MapParts.toggled(off, MapPartGroup.WEATHER))
        // with the clouds' shadows alone left out, one tap leaves the rest of the weather out too, the next takes it all in
        val cloudless = MapParts.toggled(setOf(MapPart.CLOUD_SHADOWS), MapPartGroup.WEATHER)
        assertTrue(cloudless.containsAll(weather))
        val on = MapParts.toggled(cloudless, MapPartGroup.WEATHER)
        assertTrue(on.none { it in weather })
        // what other groups' switches left out stays as it was
        val dry = MapPartGroup.WATER_LIGHT.parts
        val twice = MapParts.toggled(MapParts.toggled(dry, MapPartGroup.WEATHER), MapPartGroup.WEATHER)
        assertTrue(twice.containsAll(dry - weather))
        // all of the buildings are their walls, roofs and edges and every part of their facades
        assertEquals(MapPartGroup.BUILDINGS.listed.toSet() + MapPartGroup.FACADES.parts, MapPartGroup.BUILDINGS.parts)
        assertEquals(
            setOf(MapPart.BUILDING_SHADOWS, MapPart.SHADOW_BLUR, MapPart.PARK_SHADOWS, MapPart.CLOUD_SHADOWS),
            MapPartGroup.SHADOWS.parts,
        )
        // one part's switch alone
        assertEquals(MapParts.byDefault + MapPart.RAIN, MapParts.toggled(MapParts.byDefault, MapPart.RAIN))
        assertEquals(setOf(MapPart.RAIN), MapParts.toggled(setOf(MapPart.RAIN, MapPart.RIPPLE), MapPart.RIPPLE))
    }

    @Test
    fun thePartsReadBackFromTheirNames() {
        assertEquals(setOf(MapPart.RAIN, MapPart.LIT_STREETS), MapParts.named(setOf("RAIN", "LIT_STREETS", "NO_SUCH_PART")))
    }
}
