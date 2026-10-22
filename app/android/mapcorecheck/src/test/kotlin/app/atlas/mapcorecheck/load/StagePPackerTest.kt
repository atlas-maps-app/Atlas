package app.atlas.mapcorecheck.load

import app.atlas.rendertiles.compile.CoreTiles
import app.atlas.rendertiles.compile.SceneToTiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * The packer on the core's stage P (ENGINE.md step 3): the bundled render packs made by the packer with the core's tool
 * (`atlas_stagep`, `-Datlas.stagep`) cutting their tiles are the packer's own bundled packs (`-Datlas.tiles`, cut by the
 * port), byte for byte: the Westerkerk's living map and the canal ring, every tile, the manifest, the head and the style.
 * So a pack and a window the phone builds from the same data come from the same code.
 */
class StagePPackerTest {
    private val tool = System.getProperty("atlas.stagep")?.let(::File)
    private val repo = System.getProperty("atlas.repo")?.let(::File)

    @Test
    fun theBundledPacksCutByTheCoresStagePAreThePackersOwnByteForByte() {
        assumeTrue("the core's stage P tool (-Datlas.stagep)", tool != null && tool.canExecute())
        val tiles = File(System.getProperty("atlas.tiles") ?: error("atlas.tiles names no bundled render packs"))
        val root = repo ?: error("atlas.repo names no repository")
        val sources = listOf(
            "amsterdam" to File(root, "app/android/app/src/main/assets/packs/amsterdam.atlas"),
            "grachten" to File(root, "cache/packs/regions/grachten.atlas"),
        )
        val work = Files.createTempDirectory("atlas-stagep").toFile()
        try {
            for ((id, element) in sources) {
                if (!element.isFile) {
                    println("stagep: no element pack ${element.path}: $id is not compared here")
                    continue
                }
                val want = File(tiles, "$id.atlas")
                assertTrue("no bundled render pack ${want.path}", want.isFile)
                val made = File(work, "$id.atlas")
                val t0 = System.nanoTime()
                val p = SceneToTiles.pack(element, made, File(work, "engine-$id"), core = tool) { println("stagep $it") }
                println("stagep $id: ${p.written.chunks.size} tiles, ${p.written.bytes} bytes in ${(System.nanoTime() - t0) / 1_000_000} ms")
                assertEquals("$id: the pack cut by the core's stage P is not the packer's: ${difference(made.readBytes(), want.readBytes())}", "the same", difference(made.readBytes(), want.readBytes()))
            }
        } finally {
            work.deleteRecursively()
        }
    }

    @Test
    fun theToolsRefusalsAndItsTilesFile() {
        assumeTrue("the core's stage P tool (-Datlas.stagep)", tool != null && tool.canExecute())
        // a usage error says so, with exit code 2
        val p = ProcessBuilder(tool!!.path).redirectErrorStream(true).start()
        val said = p.inputStream.bufferedReader().readText()
        assertEquals(2, p.waitFor())
        assertTrue(said, said.startsWith("usage: atlas_stagep tiles"))
        // a tiles file that is not one is refused, never read as tiles
        val bad = runCatching { CoreTiles.read("ATSQ".toByteArray()) }.exceptionOrNull()
        assertTrue("$bad", bad is IOException)
        val short = runCatching { CoreTiles.read(byteArrayOf(65, 84, 83, 80, 1, 0, 0, 0, 1, 0, 0, 0, 14)) }.exceptionOrNull()
        assertTrue("$short", short is IOException && "ends early" in short.message!!)
        assertEquals(0, CoreTiles.read(byteArrayOf(65, 84, 83, 80, 1, 0, 0, 0, 0, 0, 0, 0)).size)
    }

    /** Where two byte strings first differ, or "the same". */
    private fun difference(a: ByteArray, b: ByteArray): String {
        for (i in 0 until minOf(a.size, b.size)) {
            if (a[i] != b[i]) return "byte $i: ${a[i].toInt() and 0xFF}, not ${b[i].toInt() and 0xFF}"
        }
        return if (a.size != b.size) "${a.size} bytes, not ${b.size}" else "the same"
    }
}
