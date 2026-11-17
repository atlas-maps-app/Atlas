package app.atlas.services

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.preferencesOf
import app.atlas.mapview.MapPart
import app.atlas.mapview.MapParts
import app.atlas.mapview.palette.Palette
import app.atlas.mapview.palette.PaletteCode
import app.atlas.mapview.palette.Preset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The map's parts a debug build's Look settings leave out (SettingsStore.kt's leftOutParts): their names, or unset the
 * defaults; and beside them the Fast refresh (fastRefreshOn) and the screen's haze, vignette and grain
 * (screenEffectsOn).
 */
class PartsSettingsTest {
    @Test
    fun unsetEveryPartButThePlinthsTheQuaysAndTheWatersLightButItsReflectionsIsDrawn() {
        assertEquals(MapParts.byDefault, leftOutParts(preferencesOf()))
        val off = leftOutParts(preferencesOf())
        val quaysAndLight = setOf(MapPart.PLINTHS, MapPart.QUAYS, MapPart.QUAY_TOPS, MapPart.RIPPLE, MapPart.GLINTS)
        assertTrue(off.containsAll(quaysAndLight))
        assertFalse(MapPart.REFLECTIONS in off || MapPart.ROOF_EDGES in off || MapPart.CLOUD_SHADOWS in off)
    }

    @Test
    fun thePartsLeftOutAreTheirNames() {
        val p = mutablePreferencesOf(SettingsKeys.MAP_PARTS_OFF to setOf(MapPart.RAIN.name, MapPart.LIT_STREETS.name))
        assertEquals(setOf(MapPart.RAIN, MapPart.LIT_STREETS), leftOutParts(p))
        // every part drawn
        p[SettingsKeys.MAP_PARTS_OFF] = emptySet()
        assertEquals(emptySet<MapPart>(), leftOutParts(p))
    }

    @Test
    fun theSunIsDrawnUnsetAndLeftOutByItsName() {
        assertFalse(MapPart.SUN in leftOutParts(preferencesOf()))
        val p = mutablePreferencesOf(SettingsKeys.MAP_PARTS_OFF to setOf(MapPart.SUN.name))
        assertEquals(setOf(MapPart.SUN), leftOutParts(p))
        // its bit, the camera state's 55 (CameraWriter.PART_SUN), alone
        assertEquals(1L shl 55, MapParts.bits(leftOutParts(p)))
    }

    @Test
    fun theFastRefreshIsOnUnsetAndAsSetOtherwise() {
        assertTrue(fastRefreshOn(preferencesOf()))
        val p = mutablePreferencesOf(SettingsKeys.MAP_FAST_REFRESH to false)
        assertFalse(fastRefreshOn(p))
        p[SettingsKeys.MAP_FAST_REFRESH] = true
        assertTrue(fastRefreshOn(p))
        // the parts' switches leave it alone
        p[SettingsKeys.MAP_PARTS_OFF] = setOf(MapPart.RAIN.name)
        assertTrue(fastRefreshOn(p))
    }

    @Test
    fun theScreensHazeVignetteAndGrainAreOnUnsetAndAsSetOtherwise() {
        // the user, 4 November: "Alpha 22s look by default for now. Haze, vignette and grain on"
        assertEquals(Triple(true, true, true), screenEffectsOn(preferencesOf()))
        val p = mutablePreferencesOf(SettingsKeys.MAP_VIGNETTE to false)
        assertEquals(Triple(true, false, true), screenEffectsOn(p))
        p[SettingsKeys.MAP_HAZE] = false
        p[SettingsKeys.MAP_GRAIN] = false
        assertEquals(Triple(false, false, false), screenEffectsOn(p))
        p[SettingsKeys.MAP_GRAIN] = true
        assertEquals(Triple(false, false, true), screenEffectsOn(p))
    }

    @Test
    fun unsetTheMapDrawsAlpha22sPaletteWithTheToneOff() {
        // the user, 4 November: "Alpha 22s look by default for now"; its colours since 11 November
        assertEquals(Palette(Preset.ALPHA_22), DEFAULT_PALETTE)
        assertEquals(DEFAULT_PALETTE, mapPalette(null))
        assertEquals(DEFAULT_PALETTE, mapPalette("AT1-NOPE-00000"))
        assertEquals(Palette(Preset.QUIET), mapPalette(PaletteCode.write(Palette(Preset.QUIET))))
        assertEquals(Palette(), mapPalette(PaletteCode.write(Palette())))
        assertFalse(DEFAULT_TONE_ON)
        assertEquals(0f, mapTone(preferencesOf()))
        val p = mutablePreferencesOf(SettingsKeys.MAP_TONE_ON to true)
        assertEquals(1f, mapTone(p))
        p[SettingsKeys.MAP_TONE] = 1.5f
        assertEquals(1.5f, mapTone(p))
        p[SettingsKeys.MAP_TONE_ON] = false
        assertEquals(0f, mapTone(p))
    }
}
