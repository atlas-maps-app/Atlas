package app.atlas.render.live

import app.atlas.render.data.PackReader
import app.atlas.render.py.PyCompat
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * The engine's version follows its outputs (plan-gpu G4): the fixed renders are made again, through the live engine
 * from the regions' packs as the phone makes them, and their md5 must equal [EngineVersion.OUTPUTS]. When one differs
 * the test fails with the new declaration to paste into `EngineVersion.kt`.
 *
 * The Westerkerk's pack is the app's bundled one (in git), so its four moments are checked everywhere; the Main
 * board's and San Gimignano's packs (`cache/packs/regions`) are on the owner's machine only, and their outputs are
 * checked where they are present.
 */
class EngineVersionTest {
    companion object {
        private val repo: File = File(System.getProperty("atlas.repo") ?: "../../..").canonicalFile
        private var work: File? = null
        private var workBefore: File? = null
        private var minorBefore = 9

        /** The pack a fixed render reads: the app's bundled Westerkerk, or a region's published pack. */
        fun packOf(region: String): File =
            if (region == "amsterdam") File(repo, "app/android/app/src/main/assets/packs/amsterdam.atlas")
            else File(repo, "cache/packs/regions/$region.atlas")

        /** The outputs computed in this run, by name; the ones whose pack is not here are left out. */
        private val computed = LinkedHashMap<String, String>()

        /** The Westerkerk's sunny home render's layers, for the planted change. */
        private var sunnyLayers: List<String>? = null

        @BeforeClass
        @JvmStatic
        fun renderTheFixedSet() {
            minorBefore = PyCompat.minor
            // the phone's port: CPython 3.9's last bits (the app never changes it; other test classes may have)
            PyCompat.minor = 9
            workBefore = LiveEngine.workDir
            val dir = Files.createTempDirectory("engine-version").toFile()
            work = dir
            LiveEngine.workDir = dir
            LiveEngine.forget()
            for ((region, fixed) in EngineVersion.FIXED.groupBy { it.region }) {
                val pack = packOf(region)
                if (!pack.isFile) {
                    println("EngineVersionTest: ${pack.path} is not here; ${fixed.joinToString { it.name }} not checked")
                    continue
                }
                val live = PackReader.open(pack).use { LiveEngine.open(it, sha256(pack)) }
                for (f in fixed) {
                    val r = LiveEngine.render(live, f.liveView(live), Moment.Named(f.moment)) ?: error("${f.name} was cancelled")
                    // a fixed render stands on its region's own view, so no camera maths of the platform's own reaches it
                    assertEquals("${f.name} snaps to its view", f.view, r.view)
                    assertEquals("${f.name} is drawn at its moment", f.moment, r.moment)
                    computed[f.name] = EngineVersion.md5(r)
                    if (f.name == "westerkerk-sunny") sunnyLayers = listOf(r.g, r.o, r.o2 ?: "")
                }
                LiveEngine.forget(live)
            }
        }

        @AfterClass
        @JvmStatic
        fun cleanUp() {
            LiveEngine.forget()
            workBefore?.let { LiveEngine.workDir = it }
            work?.deleteRecursively()
            PyCompat.minor = minorBefore
        }

        private fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { s ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }

    @Test
    fun theFixedOutputsAreTheEnginesOwn() {
        assertTrue("the Westerkerk (the app's bundled pack) is always checked", EngineVersion.FIXED.filter { it.region == "amsterdam" }.all { it.name in computed })
        val changed = EngineVersion.changes(computed)
        if (changed.isNotEmpty()) {
            fail(
                buildString {
                    append("the engine's fixed outputs changed, so EngineVersion must change with them (the coordinator bumps it):\n")
                    for ((name, v) in changed) append("  $name: ${v.first} -> ${v.second}\n")
                    append("paste into render/src/main/kotlin/app/atlas/render/live/EngineVersion.kt:\n")
                    append(EngineVersion.literal(computed))
                    append("which makes ID ${EngineVersion.idOf(EngineVersion.OUTPUTS + computed, EngineVersion.ANCHOR_FORMAT)}")
                },
            )
        }
        println("EngineVersionTest: ${computed.size} of ${EngineVersion.FIXED.size} fixed outputs checked, all current; ID ${EngineVersion.ID}")
    }

    @Test
    fun aChangeThatAltersAFixedOutputFailsAndOneThatDoesNotPasses() {
        val layers = sunnyLayers ?: error("the Westerkerk's sunny home was not rendered")
        val same = EngineVersion.md5(layers)
        // a change that leaves the render alone: the same layers give the same md5, so nothing is reported
        assertTrue(EngineVersion.changes(mapOf("westerkerk-sunny" to same), mapOf("westerkerk-sunny" to same)).isEmpty())
        // a change that alters the render by one character in one place: the md5 moves, the change is reported with
        // its new value, and the declaration to paste carries it
        val g = layers[0]
        val at = g.indexOf("fill=\"#") + 7
        val swapped = g.substring(0, at) + (if (g[at] == '0') '1' else '0') + g.substring(at + 1)
        val moved = EngineVersion.md5(listOf(swapped, layers[1], layers[2]))
        assertNotEquals(same, moved)
        val stored = mapOf("westerkerk-sunny" to same, "westerkerk-night" to "n")
        val changes = EngineVersion.changes(mapOf("westerkerk-sunny" to moved), stored)
        assertEquals(mapOf("westerkerk-sunny" to (same to moved)), changes)
        val literal = EngineVersion.literal(mapOf("westerkerk-sunny" to moved), stored)
        assertTrue(literal, literal.contains("\"westerkerk-sunny\" to \"$moved\""))
        assertTrue(literal, literal.contains("\"westerkerk-night\" to \"n\""))
        // a layer boundary is part of the md5: the same text split differently is another output
        assertNotEquals(EngineVersion.md5(listOf("ab", "c", "")), EngineVersion.md5(listOf("a", "bc", "")))
    }

    @Test
    fun theVersionFollowsEveryOutputAndTheFormat() {
        val base = EngineVersion.OUTPUTS
        assertEquals(EngineVersion.ID, EngineVersion.idOf(base, EngineVersion.ANCHOR_FORMAT))
        assertTrue(EngineVersion.ID, Regex("a\\d+-[0-9a-f]{16}").matches(EngineVersion.ID))
        // the order of the declaration does not matter; every value and the format do
        assertEquals(EngineVersion.ID, EngineVersion.idOf(base.entries.reversed().associate { it.key to it.value }, EngineVersion.ANCHOR_FORMAT))
        for (k in base.keys) assertNotEquals(k, EngineVersion.ID, EngineVersion.idOf(base + (k to base.getValue(k) + "0"), EngineVersion.ANCHOR_FORMAT))
        assertNotEquals(EngineVersion.ID, EngineVersion.idOf(base, EngineVersion.ANCHOR_FORMAT + 1))
    }

    @Test
    fun theDeclarationCoversTheFixedSet() {
        assertEquals(EngineVersion.FIXED.map { it.name }, EngineVersion.OUTPUTS.keys.toList())
        for ((k, v) in EngineVersion.OUTPUTS) assertTrue("$k: '$v' is an md5", Regex("[0-9a-f]{32}").matches(v))
        // the living map at four moments, and the regions engine: both engines, every moment
        assertEquals(setOf("sunny", "grey", "wet", "night"), EngineVersion.FIXED.map { it.moment }.toSet())
        assertTrue(EngineVersion.FIXED.any { it.region == "amsterdam" } && EngineVersion.FIXED.any { it.region != "amsterdam" })
    }
}
