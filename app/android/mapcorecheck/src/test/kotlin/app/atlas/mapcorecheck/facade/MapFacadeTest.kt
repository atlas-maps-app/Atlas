package app.atlas.mapcorecheck.facade

import app.atlas.mapcore.Native
import app.atlas.mapcore.state.ThemeBlock
import app.atlas.render.Amsterdam
import app.atlas.render.Regions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files

/**
 * The app's map through the façade on the JVM against the core's host build (plan-vector K11, `CP/jni/Bridge.cpp`'s
 * map, packs and moment): the packs the app lists open in the map's world (listed before it is made, opened after,
 * kept when listed again, a broken one refused with its reason), the map turns on without a window (hosts have none: it
 * draws once one comes) and off, and the moment's theme block is checked as the core reads it.
 */
class MapFacadeTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun load() {
            val path = System.getProperty("atlas.mapcore.host")
            assumeTrue("no host build (-Datlas.mapcore.host)", path != null)
            assertNull(Native.load(path))
        }
    }

    private fun packs(handle: Long, tiles: File, vararg ids: String): String = Native.packs(
        handle, arrayOf(*ids), LongArray(ids.size) { it + 1L }, IntArray(ids.size), Array(ids.size) { File(tiles, "${ids[it]}.atlas").path },
        IntArray(ids.size) { -1 }, LongArray(ids.size), LongArray(ids.size),
    )

    @Test
    fun theMapsPacksMomentAndSwitchReachTheCore() {
        val tiles = System.getProperty("atlas.tiles")?.let(::File)
        assumeTrue("the bundled render packs (-Datlas.tiles)", tiles != null && File(tiles, "amsterdam.atlas").isFile)
        tiles!!
        val dir = Files.createTempDirectory("atlas-map-facade").toFile()
        try {
            val guard = dir.resolve("vk").path
            val handle = Native.create(dir.path, guard, "jvm-test", "", 0, "", 0L)
            assertNotEquals("create refused: ${Native.lastError()}", 0L, handle)
            try {
                // Before the map is made the packs are listed; once it is, they open in its world.
                assertEquals("amsterdam listed\n", packs(handle, tiles, "amsterdam"))
                assertEquals("", Native.map(handle, 1))
                assertEquals("amsterdam kept\ngrachten opened\n", packs(handle, tiles, "amsterdam", "grachten"))
                assertEquals("amsterdam kept\ngrachten kept\n", packs(handle, tiles, "amsterdam", "grachten"))
                // A file that is no pack: refused, saying why; a number of 0 or a kind past the known ones: not listed.
                val junk = dir.resolve("junk.atlas").apply { writeText("not a pack") }
                val said = Native.packs(handle, arrayOf("junk"), longArrayOf(3), intArrayOf(1), arrayOf(junk.path), intArrayOf(-1), longArrayOf(0), longArrayOf(0))
                assertTrue(said, said.startsWith("junk: "))
                assertEquals(
                    "bad: not a pack the map can list\n",
                    Native.packs(handle, arrayOf("bad"), longArrayOf(0), intArrayOf(0), arrayOf(junk.path), intArrayOf(-1), longArrayOf(0), longArrayOf(0)),
                )
                assertEquals(
                    "packs: the arrays differ in length",
                    Native.packs(handle, arrayOf("a", "b"), longArrayOf(1), intArrayOf(0, 0), arrayOf("", ""), intArrayOf(-1, -1), longArrayOf(0, 0), longArrayOf(0, 0)),
                )

                // The moment: the port's sunny theme as the app writes it; a block of another version is refused.
                Regions.resetPlace()
                val th = Amsterdam.THEMES.getValue("sunny")
                val (az, el, sun) = Amsterdam.sunOf(th)
                val block = ThemeBlock.allocate()
                ThemeBlock.write(block, th, doubleArrayOf(sun.x, sun.y, sun.z), az, el, golden = false, tone = false)
                assertEquals("", Native.moment(handle, block))
                block.putInt(ThemeBlock.VERSION_AT, 2)
                assertEquals("the theme was refused: version", Native.moment(handle, block))
                assertEquals("the theme must be a direct buffer of 256 bytes", Native.moment(handle, ByteBuffer.allocate(256)))

                // Off, and modes the façade does not have.
                assertEquals("", Native.map(handle, 0))
                assertEquals("no map mode 7", Native.map(handle, 7))
                assertEquals("", Native.map(handle, 2)) // on again, as a TextureView's
                assertEquals("", Native.map(handle, 0))
            } finally {
                Native.close(handle)
            }
            // The close let go of the map's world behind the close stage: no marker left.
            assertTrue(!dir.resolve("vk/guard").exists())
        } finally {
            Regions.resetPlace()
            dir.deleteRecursively()
        }
    }
}
