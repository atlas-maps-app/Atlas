package app.atlas.mapcorecheck.sun

import app.atlas.rendertiles.compile.SceneToTiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The sun worker's entry points in the core's host build (`CP/jni/TestSun.cpp`, plan-vector K8), loaded from
 * `-Datlas.mapcore.host`.
 */
internal object SunHost {
    /** The layout version of these entry points; a library of another is refused. */
    const val ABI = 2

    init {
        val path = System.getProperty("atlas.mapcore.host") ?: error("atlas.mapcore.host names no host build of the core")
        require(File(path).isFile) { "no host build of the core at $path" }
        System.load(path)
        check(abi() == ABI) { "the host build's sun entry points are version ${abi()}, these tests' $ABI" }
    }

    @JvmStatic external fun abi(): Int

    /** The bundled check over the pack at [path], as the app's VK facts runs it: its line, `hash <hex>`, `ms <time>`. */
    @JvmStatic external fun bundled(path: String): String

    /** The ground's check over the pack at [path], as the app's VK facts runs it: its line, `hash <hex>`. */
    @JvmStatic external fun ground(path: String): String
}

/**
 * The sun's bundled check (CP/sun/Buckets.hpp's bundled_check_expected) and the ground's (CP/geometry/Ground.hpp's
 * ground_check_expected), which the app's VK facts runs over the Westerkerk the app carries and the emulator holds to
 * `ci/hashes.env`'s ATLAS_SUN_HASH and ATLAS_GROUND_HASH: that pack made here by :app's generateFactsTiles' own recipe
 * (the packer on the core's stage P tool, `-Datlas.stagep`, from the app's element pack: SceneToTiles.pack, as
 * `RenderTiles --renders … --core` calls it), the host build's lines are `sun <hash> ok` and `ground <hash> ok`, and
 * hashes.env pins those hashes, so a change that moves one moves both. (The twin's check needs a device: the core's own
 * test holds twin_check_expected, CP/tests/frame/twin_check_test.cpp, and the emulator ATLAS_TWIN_HASH.)
 */
class SunCheckTest {
    @Test
    fun theBundledCheckIsTheOneHashesEnvPins() {
        val tool = System.getProperty("atlas.stagep")?.let(::File)
        assumeTrue("the core's stage P tool (-Datlas.stagep)", tool != null && tool.canExecute())
        val repo = File(System.getProperty("atlas.repo") ?: error("atlas.repo names no repository"))
        val element = File(repo, "app/android/app/src/main/assets/packs/amsterdam.atlas")
        assertTrue("no element pack of the Westerkerk at $element", element.isFile)
        val work = Files.createTempDirectory("atlas-sun-check").toFile()
        try {
            val westerkerk = File(work, "amsterdam.atlas")
            SceneToTiles.pack(element, westerkerk, File(work, "engine"), core = tool) { println("stagep $it") }
            check(westerkerk)
        } finally {
            work.deleteRecursively()
        }
    }

    private fun check(westerkerk: File) {
        val out = SunHost.bundled(westerkerk.path).lines()
        println("sun bundled: ${out.joinToString("; ")}")
        val hash = out[1].removePrefix("hash ")
        assertEquals("the host build's bundled line (bundled_check_expected must be its hash, 0x%sULL)".format(hash.uppercase()), "sun $hash ok", out[0])
        val hashes = File(System.getProperty("atlas.repo") ?: error("atlas.repo names no repository"), "app/android/ci/hashes.env").readText()
        val pinned = Regex("^ATLAS_SUN_HASH=([0-9a-f]{16})$", RegexOption.MULTILINE).find(hashes)?.groupValues?.get(1)
        assertEquals("ci/hashes.env's ATLAS_SUN_HASH must be the host build's bundled hash", hash, pinned)
        val ground = SunHost.ground(westerkerk.path).lines()
        println("ground: ${ground.joinToString("; ")}")
        val groundHash = ground[1].removePrefix("hash ")
        assertEquals("the host build's ground line (ground_check_expected must be its hash, 0x%sULL)".format(groundHash.uppercase()), "ground $groundHash ok", ground[0])
        val groundPinned = Regex("^ATLAS_GROUND_HASH=([0-9a-f]{16})$", RegexOption.MULTILINE).find(hashes)?.groupValues?.get(1)
        assertEquals("ci/hashes.env's ATLAS_GROUND_HASH must be the host build's ground hash", groundHash, groundPinned)
    }
}
