package app.atlas.mapcorecheck.load

import app.atlas.mapcore.Native
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * The windows' boxes the core's stage P tool prints (`atlas_stagep window-boxes`, the boxes `packer windows` cuts each
 * window's answer for) are the boxes the phone asks Overpass for (`MapCore.Window.box()`: JNI windowBox, the core's
 * load::window_box), every double to the bit as Kotlin's toDouble reads the tool's digits back: around the Westerkerk,
 * at the world's corners and edges, across the equator and the prime meridian, and far north. A range that is none is
 * refused.
 */
class WindowBoxesTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun load() {
            val path = System.getProperty("atlas.mapcore.host")
            assumeTrue("no host build (-Datlas.mapcore.host)", path != null)
            assertNull(Native.load(path))
        }
    }

    private val tool = System.getProperty("atlas.stagep")?.let(::File)

    /** The tool's exit code and its lines. */
    private fun boxes(vararg range: String): Pair<Int, List<String>> {
        val p = ProcessBuilder(listOf(tool!!.path, "window-boxes") + range).redirectErrorStream(true).start()
        val lines = p.inputStream.bufferedReader().readLines()
        return p.waitFor() to lines
    }

    @Test
    fun theToolsWindowBoxesAreTheCoresToTheBit() {
        assumeTrue("the core's stage P tool (-Datlas.stagep)", tool != null && tool.canExecute())
        val ranges = listOf(
            intArrayOf(8410, 5380, 8419, 5389), // the Westerkerk's window, 14/8414/5384, and around it
            intArrayOf(0, 0, 2, 2), intArrayOf(16381, 16381, 16383, 16383), intArrayOf(0, 16381, 1, 16383), intArrayOf(16382, 0, 16383, 1),
            intArrayOf(8190, 8190, 8193, 8193), // the equator and the prime meridian
            intArrayOf(5000, 1000, 5003, 1003), // far north, where a degree of longitude is short
        )
        var n = 0
        for (r in ranges) {
            val (code, lines) = boxes(*r.map { it.toString() }.toTypedArray())
            assertEquals(lines.joinToString("\n"), 0, code)
            var k = 0
            for (x in r[0]..r[2]) for (y in r[1]..r[3]) {
                val v = lines[k++].split(' ')
                assertEquals("$x $y", "${v[0]} ${v[1]}")
                val want = Native.windowBox(14, x, y)
                for (i in 0 until 4) {
                    assertEquals("14/$x/$y [$i]: ${v[2 + i]} for ${want[i]}", want[i].toRawBits(), v[2 + i].toDouble().toRawBits())
                }
                n++
            }
            assertEquals(k, lines.size)
        }
        println("window-boxes: $n windows, each box the core's to the bit")
        for (bad in listOf(listOf("2", "0", "1", "0"), listOf("0", "0", "16384", "0"), listOf("a", "0", "1", "1"), listOf("-1", "0", "1", "1"))) {
            assertEquals(bad.joinToString(" "), 2, boxes(*bad.toTypedArray()).first)
        }
    }
}
