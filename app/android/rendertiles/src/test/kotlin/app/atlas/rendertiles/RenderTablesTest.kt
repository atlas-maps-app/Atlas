package app.atlas.rendertiles

import app.atlas.render.Amsterdam
import app.atlas.rendertiles.compile.GroundKeys
import app.atlas.rendertiles.compile.SceneToTiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The port's tables the packer needs that the port keeps private (`amsterdam.py`'s `ROAD_ORDER`, the building and node
 * classes) are copied in `:rendertiles`; this holds each copy equal to the port's own, read from the port by
 * reflection, so no copy drifts. `regions.py`'s runway order is a local of `draw_over`: its literal is checked in the
 * port's source. (EngineStyleTest holds the style file to the port.)
 */
class RenderTablesTest {
    private fun port(name: String): Any? = Amsterdam::class.java.getDeclaredField(name).apply { isAccessible = true }.get(Amsterdam)

    @Test
    fun `the copied tables equal the port's`() {
        assertEquals(port("ROAD_ORDER"), GroundKeys.ROAD_ORDER)
        assertEquals(port("CHURCHES"), SceneToTiles.CHURCHES)
        assertEquals(port("WAREHOUSES"), SceneToTiles.WAREHOUSES)
        assertEquals(port("SHOP_FLOORS"), SceneToTiles.SHOP_FLOORS)
        assertEquals(port("FURNITURE"), SceneToTiles.FURNITURE)
        assertEquals(port("BOLLARDS"), SceneToTiles.BOLLARDS)
    }

    @Test
    fun `the runways' order is draw_over's`() {
        val src = File(Packs.repo, "app/android/render/src/main/kotlin/app/atlas/render/Regions.kt")
        assumeTrue(src.isFile)
        val literal = "mapOf(" + GroundKeys.AERO_ORDER.entries.joinToString(", ") { (k, v) -> "\"$k\" to $v" } + ")"
        assertTrue("Regions.kt's draw_over sorts by $literal", src.readText().contains("val ORDER = $literal"))
    }
}
