package app.atlas.mapcorecheck.load

import app.atlas.mapcore.Native
import app.atlas.tiles.WindowsIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Windows the core builds from a region's windows' data (`packer windows`), as the phone builds them from a pack's: the
 * data file -Datlas.windows names (`<id>.windows`, its index `<id>.windows.idx` beside it, read by the phone's own
 * WindowsIndex), each window's bytes held to the index's SHA-256, unpacked and built by the core through the façade
 * (`MapCore.Window.build`, JNI buildWindow) with no refusal: the Westerkerk's window when the region holds it, the
 * three biggest, and twenty more across the region. On the Mac, beside an extract's windows (Noord-Holland's).
 */
class WindowPackBuildTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun load() {
            val path = System.getProperty("atlas.mapcore.host")
            assumeTrue("no host build (-Datlas.mapcore.host)", path != null)
            assertNull(Native.load(path))
        }
    }

    private val data = System.getProperty("atlas.windows")?.let(::File)

    @Test
    fun windowsBuildFromAPacksDataWithNoRefusal() {
        assumeTrue("a region's windows' data (-Datlas.windows)", data != null && data.isFile)
        val index = WindowsIndex.read(File(data!!.path + ".idx").readBytes())
        assertEquals(14, index.level)
        assertEquals(WindowsIndex.QUERY, index.query)
        assertEquals(data.length(), index.dataBytes)
        val all = (0 until index.size).map { index[it] }
        // the Westerkerk's (52.3745, 4.8840), the biggest, and the rest spread across the region
        val westerkerk = index.find(8414, 5384)
        val picked = (listOfNotNull(westerkerk) + all.sortedByDescending { it.length }.take(3) + (0 until 20).map { all[it * all.size / 20] })
            .distinctBy { it.x to it.y }
        val dir = Files.createTempDirectory("atlas-window-packs").toFile()
        try {
            RandomAccessFile(data, "r").use { file ->
                for (e in picked) {
                    val member = ByteArray(e.length)
                    file.seek(e.offset)
                    file.readFully(member)
                    assertTrue("14/${e.x}/${e.y}: its bytes are not the index's", MessageDigest.getInstance("SHA-256").digest(member).contentEquals(e.sha256))
                    val json = File(dir, "14-${e.x}-${e.y}.json")
                    GZIPInputStream(member.inputStream()).use { input -> json.outputStream().use { input.copyTo(it) } }
                    val pack = File(dir, "14-${e.x}-${e.y}.atlas")
                    val t0 = System.nanoTime()
                    val said = Native.buildWindow(14, e.x, e.y, json.path, pack.path)
                    val ms = (System.nanoTime() - t0) / 1_000_000
                    assertEquals("14/${e.x}/${e.y}: the core refused its data", "", said)
                    assertTrue(pack.isFile)
                    println("window 14/${e.x}/${e.y}: ${e.length} bytes gzipped, ${json.length()} of JSON, a pack of ${pack.length()} bytes in $ms ms")
                    json.delete()
                    pack.delete()
                }
            }
        } finally {
            dir.deleteRecursively()
        }
        if (data.name.startsWith("noord-holland")) assertNotNull("Noord-Holland's windows hold the Westerkerk's", westerkerk)
        println("window packs: ${picked.size} of ${index.size} windows built from ${data.name}")
    }
}
