package app.atlas.mapview

import app.atlas.mapcore.state.CameraWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The map's parts (MapParts.kt): a bit each of the camera state's parts, every one once; the defaults the map has drawn
 * since alpha 19 (the clouds' shadows and the water's light off); and the group switches.
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
    fun theCloudsShadowsAndTheWatersLightAreLeftOutByDefault() {
        assertEquals(setOf(MapPart.CLOUD_SHADOWS) + MapPartGroup.WATER_LIGHT.parts, MapParts.byDefault)
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
        // the defaults leave the clouds' shadows out: one tap leaves the rest of the weather out too, the next takes it all in
        val off = MapParts.toggled(MapParts.byDefault, MapPartGroup.WEATHER)
        assertTrue(off.containsAll(weather))
        val on = MapParts.toggled(off, MapPartGroup.WEATHER)
        assertTrue(on.none { it in weather })
        // what other groups list stays as it was
        assertTrue(on.containsAll(MapPartGroup.WATER_LIGHT.parts - weather))
        // all of the buildings are their walls, roofs and edges and every part of their facades
        assertEquals(MapPartGroup.BUILDINGS.listed.toSet() + MapPartGroup.FACADES.parts, MapPartGroup.BUILDINGS.parts)
        assertEquals(
            setOf(MapPart.BUILDING_SHADOWS, MapPart.SHADOW_BLUR, MapPart.PARK_SHADOWS, MapPart.CLOUD_SHADOWS),
            MapPartGroup.SHADOWS.parts,
        )
        // one part's switch alone
        assertEquals(MapParts.byDefault + MapPart.RAIN, MapParts.toggled(MapParts.byDefault, MapPart.RAIN))
        assertEquals(MapParts.byDefault - MapPart.RIPPLE, MapParts.toggled(MapParts.byDefault, MapPart.RIPPLE))
    }

    @Test
    fun thePartsReadBackFromTheirNames() {
        assertEquals(setOf(MapPart.RAIN, MapPart.LIT_STREETS), MapParts.named(setOf("RAIN", "LIT_STREETS", "NO_SUCH_PART")))
    }
}
