package app.atlas.services

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.preferencesOf
import app.atlas.mapview.MapPart
import app.atlas.mapview.MapPartGroup
import app.atlas.mapview.MapParts
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The map's parts a debug build's Look settings leave out (SettingsStore.kt's leftOutParts): their names once a part is
 * switched; until then as the three switches before them had them, so nothing a phone draws changes with the update.
 */
class PartsSettingsTest {
    @Test
    fun unsetTheCloudsShadowsAndTheWatersLightAreLeftOutAsBefore() {
        assertEquals(MapParts.byDefault, leftOutParts(preferencesOf()))
    }

    @Test
    fun theSwitchesBeforeThePartsDecideUntilAPartIsSwitched() {
        val p = mutablePreferencesOf(SettingsKeys.MAP_CLOUD_SHADOWS to true, SettingsKeys.MAP_WATER_LIGHT to true, SettingsKeys.MAP_FACADES to false)
        assertEquals(MapPartGroup.FACADES.parts, leftOutParts(p))
        p[SettingsKeys.MAP_PARTS_OFF] = setOf(MapPart.RAIN.name, MapPart.LIT_STREETS.name)
        assertEquals(setOf(MapPart.RAIN, MapPart.LIT_STREETS), leftOutParts(p))
        // every part drawn
        p[SettingsKeys.MAP_PARTS_OFF] = emptySet()
        assertEquals(emptySet<MapPart>(), leftOutParts(p))
    }
}
