package app.atlas.services

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.preferencesOf
import app.atlas.mapview.MapPart
import app.atlas.mapview.MapParts
import org.junit.Assert.assertEquals
import org.junit.Test

/** The map's parts a debug build's Look settings leave out (SettingsStore.kt's leftOutParts): their names, or unset the defaults. */
class PartsSettingsTest {
    @Test
    fun unsetTheCloudsShadowsAndTheWatersLightAreLeftOut() {
        assertEquals(MapParts.byDefault, leftOutParts(preferencesOf()))
    }

    @Test
    fun thePartsLeftOutAreTheirNames() {
        val p = mutablePreferencesOf(SettingsKeys.MAP_PARTS_OFF to setOf(MapPart.RAIN.name, MapPart.LIT_STREETS.name))
        assertEquals(setOf(MapPart.RAIN, MapPart.LIT_STREETS), leftOutParts(p))
        // every part drawn
        p[SettingsKeys.MAP_PARTS_OFF] = emptySet()
        assertEquals(emptySet<MapPart>(), leftOutParts(p))
    }
}
